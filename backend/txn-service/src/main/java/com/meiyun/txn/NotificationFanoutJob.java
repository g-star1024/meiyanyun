package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通知多渠道扇出 Job（域⑦）。
 *
 * <p>范式复刻 {@link ApprovalSlaJob}/{@link FinanceEventRetryJob}：{@code @Scheduled} 限批、
 * 单条 try-catch、SLF4J 日志。每 60 秒一轮：
 * <ol>
 *   <li>取尚未扇出（无 delivery 记录）的通知，按收件人 NotifyPreference 渠道位逐渠道建投递记录并执行；
 *       建行在独立短事务（{@link TransactionTemplate}）内幂等补齐，崩溃在任意渠道之间重启也不漏渠道；</li>
 *   <li>INBOX 即 notification 表行本身，标记 SENT 并经 SSE 实时推送铃铛；</li>
 *   <li>非紧急通知在免打扰时段内对非 INBOX 渠道置 DEFERRED，按退避窗口延后重试；</li>
 *   <li>对 PENDING（崩溃残行自愈）/FAILED/DEFERRED 且退避窗口已到的投递做重试（限批 100）；</li>
 *   <li>重试上限 {@link #MAX_ATTEMPTS} 或网关 4xx 确定性拒绝 → DEAD，不再自动投递，留待人工。</li>
 * </ol>
 *
 * <p>幂等：notification_delivery 唯一约束 (notification_id, channel) + NOT EXISTS 查询 +
 * 建行冲突重查，保证同通知同渠道只处理一次；重启动可自愈。渠道之间逐条 try-catch，
 * 单渠道异常/唯一冲突不影响同通知其余渠道。外发 HTTP 由 {@code TxnSchedulerConfig} 的
 * 独立调度线程池承载，不长期占用其它 @Scheduled 任务的线程。
 */
@Component
public class NotificationFanoutJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutJob.class);

    /** 单渠道最大外发尝试次数：超过后置 DEAD 并告警，防永久毒消息（对齐 FinanceEventRetryJob）。 */
    private static final int MAX_ATTEMPTS = 20;
    /** 失败重试退避基数/上限（秒）：30s 起步指数退避，封顶 30 分钟。 */
    private static final long BACKOFF_BASE_SECONDS = 30L;
    private static final long BACKOFF_CAP_SECONDS = 1800L;
    /** 免打扰延后重评间隔（秒）：半小时后再看是否仍在免打扰窗口。 */
    private static final long DEFER_RECHECK_SECONDS = 1800L;

    static final String CH_INBOX = "INBOX";
    private static final Set<String> KNOWN_CHANNELS = Set.of("INBOX", "SMS", "WECHAT", "EMAIL");
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final NotificationRepository notificationRepo;
    private final NotifyPreferenceRepository preferenceRepo;
    private final NotificationDeliveryRepository deliveryRepo;
    private final List<NotificationChannelAdapter> adapters;
    private final NotificationQuietConfig quietConfig;
    private final NotificationStreamRegistry streamRegistry;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NotificationFanoutJob(NotificationRepository notificationRepo,
                                NotifyPreferenceRepository preferenceRepo,
                                NotificationDeliveryRepository deliveryRepo,
                                List<NotificationChannelAdapter> adapters,
                                NotificationQuietConfig quietConfig,
                                NotificationStreamRegistry streamRegistry,
                                TransactionTemplate txTemplate) {
        this.notificationRepo = notificationRepo;
        this.preferenceRepo = preferenceRepo;
        this.deliveryRepo = deliveryRepo;
        this.adapters = adapters;
        this.quietConfig = quietConfig;
        this.streamRegistry = streamRegistry;
        this.txTemplate = txTemplate;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void fanout() {
        List<Notification> unfanned = deliveryRepo.findUnfanned(PageRequest.of(0, 100));
        for (Notification n : unfanned) {
            try {
                fanoutOne(n);
            } catch (Exception ex) {
                log.warn("通知扇出异常 notificationId={}: {}", n.getId(), ex.getMessage());
            }
        }

        List<NotificationDelivery> retry =
                deliveryRepo.findRetryable(OffsetDateTime.now(), PageRequest.of(0, 100));
        for (NotificationDelivery d : retry) {
            try {
                retryOne(d);
            } catch (Exception ex) {
                log.warn("通知投递重试异常 deliveryId={}: {}", d.getId(), ex.getMessage());
            }
        }
    }

    private void fanoutOne(Notification n) {
        NotifyPreference pref = preferenceRepo.findByStaffIdAndCategory(n.getRecipient(), n.getCategory())
                .orElse(null);
        boolean enabled = pref == null || pref.isEnabled();
        if (!enabled) {
            // 收件人已关闭该类别订阅（免打扰）：不落任何渠道（含 INBOX），仍建一条 SKIPPED 防止重复处理
            saveIdempotent(n.getId(), CH_INBOX, "SKIPPED", "收件人已关闭该类别通知订阅（免打扰）");
            return;
        }
        List<String> targets = pref == null || pref.getChannels() == null || pref.getChannels().isBlank()
                ? List.of(CH_INBOX)
                : splitChannels(pref.getChannels());
        if (targets.isEmpty()) {
            targets = List.of(CH_INBOX);
        }
        // 逐渠道独立短事务幂等建行并投递：任一渠道失败/冲突不影响其余渠道，崩溃重启可补齐漏建渠道
        for (String ch : targets) {
            try {
                NotificationDelivery d = ensurePending(n, ch);
                if (d != null) {
                    deliver(d, n, pref);
                }
            } catch (Exception ex) {
                log.warn("通知渠道处理异常 notificationId={} channel={}: {}", n.getId(), ch, ex.getMessage());
            }
        }
    }

    /**
     * 在独立短事务内为 (notificationId, channel) 幂等补一条 PENDING 行。
     * <ul>
     *   <li>已存在且为 PENDING（崩溃残行/并发刚建）→ 返回该行当轮继续投递；</li>
     *   <li>已存在且为终态（SENT/SKIPPED/DEAD/DEFERRED/FAILED 已排期）→ 返回 null，交重试扫描；</li>
     *   <li>并发唯一冲突会令本短事务回滚并抛出，由调用方逐渠道 catch，不影响其余渠道。</li>
     * </ul>
     */
    private NotificationDelivery ensurePending(Notification n, String channel) {
        return txTemplate.execute(status ->
                deliveryRepo.findByNotificationIdAndChannel(n.getId(), channel)
                        .map(existing -> "PENDING".equals(existing.getStatus()) ? existing : null)
                        .orElseGet(() -> {
                            NotificationDelivery d = new NotificationDelivery();
                            d.setNotificationId(n.getId());
                            d.setChannel(channel);
                            d.setStatus("PENDING");
                            d.setPayload(toPayload(n));
                            return deliveryRepo.save(d);
                        }));
    }

    /** 幂等落一条终态/说明行（如关闭订阅的 INBOX SKIPPED），冲突时忽略。 */
    private void saveIdempotent(Long notificationId, String channel, String terminalStatus, String reason) {
        try {
            txTemplate.executeWithoutResult(tx -> {
                if (deliveryRepo.findByNotificationIdAndChannel(notificationId, channel).isEmpty()) {
                    NotificationDelivery d = new NotificationDelivery();
                    d.setNotificationId(notificationId);
                    d.setChannel(channel);
                    d.setStatus(terminalStatus);
                    d.setLastError(reason);
                    try {
                        deliveryRepo.save(d);
                    } catch (DataIntegrityViolationException ignore) {
                        // 并发已建行，幂等忽略
                    }
                }
            });
        } catch (DataIntegrityViolationException ignore) {
            // 极端并发下事务级冲突，忽略
        }
    }

    private void deliver(NotificationDelivery d, Notification n, NotifyPreference pref) {
        if (CH_INBOX.equals(d.getChannel())) {
            d.setStatus("SENT");
            d.setAttemptCount(d.getAttemptCount() + 1);
            d.setDeliveredAt(OffsetDateTime.now());
            d.setNextAttemptAt(null);
            deliveryRepo.save(d);
            streamRegistry.push(n.getRecipient(), n); // 实时推铃铛
            return;
        }
        if (quietConfig.inQuietWindow(n.getLevel())) {
            d.setStatus("DEFERRED");
            d.setLastError("免打扰时段，延后发送");
            d.setNextAttemptAt(OffsetDateTime.now(BIZ_ZONE).plusSeconds(DEFER_RECHECK_SECONDS));
            deliveryRepo.save(d);
            return;
        }
        if (!KNOWN_CHANNELS.contains(d.getChannel())) {
            d.setStatus("SKIPPED");
            d.setLastError("未知渠道码：" + d.getChannel());
            d.setNextAttemptAt(null);
            deliveryRepo.save(d);
            return;
        }
        NotificationChannelAdapter adapter = adapters.stream()
                .filter(a -> a.supports(d.getChannel()))
                .findFirst()
                .orElse(null);
        if (adapter == null) {
            d.setStatus("SKIPPED");
            d.setLastError("无对应渠道适配器：" + d.getChannel());
            d.setNextAttemptAt(null);
            deliveryRepo.save(d);
            return;
        }
        NotificationChannelAdapter.DeliveryResult r = adapter.send(n, d.getChannel(), pref);
        d.setAttemptCount(d.getAttemptCount() + 1);
        d.setStatus(r.status());
        d.setLastError(clip(r.errorMessage()));
        if ("SENT".equals(r.status())) {
            d.setDeliveredAt(OffsetDateTime.now());
            d.setNextAttemptAt(null);
            deliveryRepo.save(d);
        } else if ("SKIPPED".equals(r.status()) || "DEAD".equals(r.status())) {
            d.setNextAttemptAt(null);
            deliveryRepo.save(d);
            if ("DEAD".equals(r.status())) {
                log.error("通知渠道投递置 DEAD（需人工介入）deliveryId={} notificationId={} channel={} reason={}",
                        d.getId(), n.getId(), d.getChannel(), r.errorMessage());
            }
        } else if ("DEFERRED".equals(r.status())) {
            d.setNextAttemptAt(OffsetDateTime.now(BIZ_ZONE).plusSeconds(DEFER_RECHECK_SECONDS));
            deliveryRepo.save(d);
        } else {
            scheduleRetry(d);
        }
    }

    /** FAILED 退避重试或转 DEAD；DEFERRED 按固定重评间隔延后。 */
    private void scheduleRetry(NotificationDelivery d) {
        if ("DEFERRED".equals(d.getStatus())) {
            d.setNextAttemptAt(OffsetDateTime.now(BIZ_ZONE).plusSeconds(DEFER_RECHECK_SECONDS));
            deliveryRepo.save(d);
            return;
        }
        if (d.getAttemptCount() >= MAX_ATTEMPTS) {
            d.setStatus("DEAD");
            d.setLastError(clip("重试 " + d.getAttemptCount() + " 次仍失败，停止自动投递：" + d.getLastError()));
            d.setNextAttemptAt(null);
            deliveryRepo.save(d);
            log.error("通知渠道投递重试上限置 DEAD（需人工介入）deliveryId={} channel={} attempts={}",
                    d.getId(), d.getChannel(), d.getAttemptCount());
            return;
        }
        long backoff = Math.min(BACKOFF_CAP_SECONDS,
                BACKOFF_BASE_SECONDS * (1L << Math.min(d.getAttemptCount() - 1, 6)));
        d.setNextAttemptAt(OffsetDateTime.now(BIZ_ZONE).plusSeconds(backoff));
        deliveryRepo.save(d);
        log.warn("通知渠道投递失败，退避 {}s 后重试 deliveryId={} channel={} 第{}次: {}",
                backoff, d.getId(), d.getChannel(), d.getAttemptCount(), d.getLastError());
    }

    private void retryOne(NotificationDelivery d) {
        Notification n = notificationRepo.findById(d.getNotificationId()).orElse(null);
        if (n == null) {
            d.setStatus("SKIPPED");
            d.setLastError("原通知已不存在");
            d.setNextAttemptAt(null);
            deliveryRepo.save(d);
            return;
        }
        if (CH_INBOX.equals(d.getChannel())) {
            // INBOX 残行（上一轮落 PENDING 后宕机）：补推铃铛并置 SENT
            d.setStatus("SENT");
            d.setAttemptCount(d.getAttemptCount() + 1);
            d.setDeliveredAt(OffsetDateTime.now());
            d.setNextAttemptAt(null);
            deliveryRepo.save(d);
            streamRegistry.push(n.getRecipient(), n);
            return;
        }
        NotifyPreference pref = preferenceRepo.findByStaffIdAndCategory(n.getRecipient(), n.getCategory())
                .orElse(null);
        deliver(d, n, pref);
    }

    private String toPayload(Notification n) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "recipient", n.getRecipient(),
                    "title", n.getTitle(),
                    "content", n.getContent(),
                    "category", n.getCategory(),
                    "level", n.getLevel()));
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private static List<String> splitChannels(String raw) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (!t.isBlank()) {
                    seen.add(t);
                }
            }
        }
        out.addAll(seen);
        return out;
    }
}
