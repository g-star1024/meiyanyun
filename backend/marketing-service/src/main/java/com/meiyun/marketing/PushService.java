package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.common.event.DomainEventPublisher;
import com.meiyun.common.ratelimit.RateLimiter;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 触达推送写链路（M5-02 精准推送）。
 *
 * <p>校验顺序：渠道白名单（SMS/WECOM/WECHAT_MP）→ 内容 ≤256 字 → 违禁词四类实时校验 →
 * 周频控（近 7 天每客户 ≤ weekly_push_limit 条）→ 幂等重放（60 秒窗口同客户/渠道/内容直接返回已落库记录）。
 * 通过后落 push_record、发领域事件、全动作审计（audit_log，bizType=PUSH）。
 *
 * <p>B22 收口：原 Controller 内联逻辑无审计、无幂等，双击/重试会产生重复触达且不在审计链上；
 * 本服务补齐四件套（参数校验 / 幂等 / 审计 / 中文错误）。
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    /** 周频窗口：近 7 天。 */
    public static final int WINDOW_SECONDS = 7 * 24 * 3600;
    /** 幂等窗口：60 秒内同客户/渠道/内容视为重复提交。 */
    public static final int DEDUP_SECONDS = 60;
    public static final String TOPIC = "meiyun.marketing.push-sent";
    /** 合法推送渠道码（与前端 PushChannel 契约一致；push_type 列 varchar(16)）。 */
    public static final Set<String> PUSH_TYPES = Set.of("SMS", "WECOM", "WECHAT_MP");
    public static final int CONTENT_MAX = 256;

    private final PushRecordRepository pushRepo;
    private final ForbiddenWordService forbiddenWordService;
    private final MarketingCfgService cfgService;
    private final RateLimiter rateLimiter;
    private final DomainEventPublisher events;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PushService(PushRecordRepository pushRepo,
                       ForbiddenWordService forbiddenWordService,
                       MarketingCfgService cfgService,
                       RateLimiter rateLimiter,
                       DomainEventPublisher events,
                       AuditRecorder audit) {
        this.pushRepo = pushRepo;
        this.forbiddenWordService = forbiddenWordService;
        this.cfgService = cfgService;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.audit = audit;
    }

    @Transactional
    public PushRecord send(MarketingController.PushCmd cmd) {
        if (cmd == null || cmd.customerId() == null || cmd.customerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择触达客户");
        }
        if (!PUSH_TYPES.contains(cmd.pushType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "推送渠道不合法：仅支持 短信(SMS)/企业微信(WECOM)/微信公众号(WECHAT_MP)");
        }
        if (cmd.content() == null || cmd.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "推送内容不能为空");
        }
        if (cmd.content().length() > CONTENT_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "推送内容过长：最多 " + CONTENT_MAX + " 字，当前 " + cmd.content().length() + " 字");
        }
        String customerId = cmd.customerId().trim();
        String content = cmd.content().trim();

        // 红线②：违禁词校验（DB 词库 + 缓存，管理端可维护）
        List<String> hits = forbiddenWordService.check(content);
        if (!hits.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "营销合规拦截：命中违禁词 " + String.join("; ", hits));
        }

        // 幂等：60 秒窗口同客户/渠道/内容直接返回已落库记录（防双击/重试重复触达，不占频控额度、不重复审计）
        String dedupKey = dedupKey(customerId, cmd.pushType(), content);
        OffsetDateTime dedupSince = OffsetDateTime.now().minusSeconds(DEDUP_SECONDS);
        List<PushRecord> recent = pushRepo.findRecentByDedupKey(dedupKey, dedupSince);
        if (!recent.isEmpty()) {
            log.info("推送幂等命中：customer={} type={} 复用 pushId={}", customerId, cmd.pushType(), recent.get(0).getPushId());
            return recent.get(0);
        }

        // 红线①：周频限制（经 RateLimiter 抽象：DB 计数默认 / Redis 原子计数生产，配置切换）
        Integer cfgLimit = cfgService.get().getWeeklyPushLimit();
        int limit = cfgLimit == null ? 3 : cfgLimit;
        String rlKey = "push:customer:" + customerId;
        if (!rateLimiter.tryAcquire(rlKey, limit, WINDOW_SECONDS)) {
            long sent = rateLimiter.currentCount(rlKey, WINDOW_SECONDS);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "触达频控拦截：客户 " + customerId + " 近 7 天已触达 " + sent + " 条，上限 " + limit);
        }

        PushRecord p = new PushRecord();
        p.setCustomerId(customerId);
        p.setPushType(cmd.pushType());
        p.setContent(content);
        p.setDedupKey(dedupKey);
        p.setSentAt(OffsetDateTime.now());
        PushRecord saved = pushRepo.save(p);

        // 发布领域事件（默认日志实现；生产切 MQ，由 Outbox 兜底补偿）
        events.publish(TOPIC, String.valueOf(saved.getPushId()),
                "{\"pushId\":\"" + saved.getPushId() + "\",\"customerId\":\"" + customerId
                        + "\",\"pushType\":\"" + cmd.pushType() + "\"}");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pushId", saved.getPushId());
        payload.put("customerId", customerId);
        payload.put("pushType", cmd.pushType());
        payload.put("contentPreview", content.length() > 50 ? content.substring(0, 50) + "…" : content);
        payload.put("contentLength", content.length());
        audit("SEND", String.valueOf(saved.getPushId()), payload);
        return saved;
    }

    /** 幂等键 = SHA-256(客户|渠道|内容) 前 16 位十六进制。 */
    private String dedupKey(String customerId, String pushType, String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((customerId + "|" + pushType + "|" + content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            // SHA-256 为 JDK 内置算法，理论不会缺失；退化用客户+渠道兜底（幂等粒度变粗但不阻断发送）
            return (customerId + pushType).hashCode() + "";
        }
    }

    private void audit(String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record("PUSH", txnNo, DataScope.currentActor(), action, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            audit.record("PUSH", txnNo, DataScope.currentActor(), action, "{}");
        }
    }
}
