package com.meiyun.txn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 审批 SLA 超时扫描 + 催办通知（B20 审批 SLA 收口）。
 *
 * <p>范式复刻 {@link FinanceEventRetryJob}：{@code @Scheduled} 限批 50 条 FIFO、单条 try-catch、
 * 不加方法级 @Transactional（每条独立 save，单条失败不回滚整批）、SLF4J 日志。每分钟一轮：
 * <ol>
 *   <li>取最早 50 条 PENDING 待办；历史单 dueAt 为空时按当前阶段时长惰性回填（兼容 B20 前老单）；</li>
 *   <li>dueAt 已过 → overdue=true（前端红色超时标记），并进入催办判定；</li>
 *   <li>催办节流：距上次催办不足 {@code meiyun.approval.sla.remind-interval-minutes}（默认 120 分钟）
 *       不重复通知，防刷屏；首轮（从未催办）超时即催。</li>
 * </ol>
 *
 * <p>催办目标人：assignee 非空（已转交/指派）→ 仅催本人；否则按当前阶段角色路由——
 * REVIEW 店长（本门店 STORE_MGR）、REGION 区域经理（门店所属区域 REGION_MGR，由 org 侧解析）、
 * FINANCE 财务（全量 FINANCE）。org 不可用软降级为空（本轮不催，下轮自愈），不影响审批主链路。
 * 通知落 notification 表（idemKey 幂等：SLA:{todoNo}:{recipient}:{remindCount+1}），
 * 前端通知中心 / 铃铛拉 GET /api/txn/notifications 呈现。
 */
@Component
public class ApprovalSlaJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalSlaJob.class);

    private final ApprovalTodoRepository todoRepo;
    private final NotificationRepository notificationRepo;
    private final OrgStaffClient orgStaffClient;
    private final NotifyPreferenceRepository preferenceRepo;

    /** REVIEW 店长/运营一审 SLA（小时），与 ApprovalService 配置同键同默认。 */
    @org.springframework.beans.factory.annotation.Value("${meiyun.approval.sla.review-hours:24}")
    private long slaReviewHours;
    @org.springframework.beans.factory.annotation.Value("${meiyun.approval.sla.region-hours:8}")
    private long slaRegionHours;
    @org.springframework.beans.factory.annotation.Value("${meiyun.approval.sla.finance-hours:4}")
    private long slaFinanceHours;
    /** 催办节流间隔（分钟）：同一待办距上次催办不足该间隔不重复通知。 */
    @org.springframework.beans.factory.annotation.Value("${meiyun.approval.sla.remind-interval-minutes:120}")
    private long remindIntervalMinutes;

    public ApprovalSlaJob(ApprovalTodoRepository todoRepo, NotificationRepository notificationRepo,
                          OrgStaffClient orgStaffClient, NotifyPreferenceRepository preferenceRepo) {
        this.todoRepo = todoRepo;
        this.notificationRepo = notificationRepo;
        this.orgStaffClient = orgStaffClient;
        this.preferenceRepo = preferenceRepo;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 20_000L)
    public void scanOverdue() {
        List<ApprovalTodo> pending = todoRepo.findFirst50ByStatusOrderBySubmittedAtAsc("PENDING");
        if (pending.isEmpty()) return;
        OffsetDateTime now = OffsetDateTime.now();
        for (ApprovalTodo t : pending) {
            try {
                scanOne(t, now);
            } catch (Exception ex) {
                // 单条异常不影响同批其余待办
                log.warn("审批 SLA 扫描兜底异常 todoNo={}: {}", t.getTodoNo(), ex.getMessage());
            }
        }
    }

    private void scanOne(ApprovalTodo t, OffsetDateTime now) {
        // 历史单（B20 前提交，dueAt 为空）惰性回填：按当前阶段时长从提交时刻起算
        if (t.getDueAt() == null) {
            t.setDueAt(t.getSubmittedAt() == null ? now : t.getSubmittedAt().plusHours(stageSlaHours(t.getStage())));
        }
        if (!t.getDueAt().isBefore(now)) {
            // 未到截止时间：若此前已标记 overdue（理论上不会），保持；正常直接返回
            return;
        }
        boolean changed = false;
        if (!t.isOverdue()) {
            t.setOverdue(true);
            changed = true;
        }
        // 催办节流：首轮（lastRemindedAt 为空）超时即催；之后距上次不足间隔分钟不重复催
        OffsetDateTime last = t.getLastRemindedAt();
        if (last != null && Duration.between(last, now).toMinutes() < remindIntervalMinutes) {
            if (changed) todoRepo.save(t);
            return;
        }
        int newCount = t.getRemindCount() + 1;
        int notified = sendReminders(t, newCount, now);
        t.setRemindCount(newCount);
        t.setLastRemindedAt(now);
        todoRepo.save(t);
        log.info("审批 SLA 超时催办 todoNo={} stage={} tier={} 第{}轮 通知{}人 dueAt={}",
                t.getTodoNo(), t.getStage(), t.getSignTier(), newCount, notified, t.getDueAt());
    }

    /** 按当前阶段/指派人解析目标人并落催办通知（idemKey 幂等），返回实际落通知人数。 */
    private int sendReminders(ApprovalTodo t, int round, OffsetDateTime now) {
        Set<String> recipients = resolveRecipients(t);
        if (recipients.isEmpty()) {
            log.warn("审批 SLA 催办无目标人 todoNo={} stage={} store={}（可能 org 暂不可用或无在职角色人）",
                    t.getTodoNo(), t.getStage(), t.getStoreCode());
            return 0;
        }
        String stageLabel = stageLabel(t.getStage());
        String amount = t.getAmount() == null ? "" : "（¥" + (t.getAmount() / 100.0) + "）";
        String title = "审批超时催办：" + t.getTitle();
        String content = String.format("单号 %s（业务单 %s）%s%s已超过 %s 审批时限，请尽快处理。",
                t.getTodoNo(), t.getBizNo(), stageLabel, amount, stageLabel);
        int n = 0;
        int muted = 0;
        for (String staffId : recipients) {
            // B21 通知偏好：收件人显式关闭 APPROVAL 类别订阅则免打扰（不落 INBOX；无偏好行=默认订阅）
            if (preferenceRepo.findByStaffIdAndCategory(staffId, "APPROVAL")
                    .filter(p -> !p.isEnabled()).isPresent()) {
                muted++;
                continue;
            }
            String idemKey = "SLA:" + t.getTodoNo() + ":" + staffId + ":" + round;
            if (notificationRepo.existsByIdemKey(idemKey)) continue;
            Notification note = new Notification();
            note.setRecipient(staffId);
            note.setCategory("APPROVAL");
            note.setLevel("URGENT");
            note.setTitle(truncate(title, 128));
            note.setContent(truncate(content, 500));
            note.setLink("/approval");
            note.setBizRef(t.getTodoNo());
            note.setSender("system");
            note.setIdemKey(idemKey);
            note.setCreatedAt(now);
            notificationRepo.save(note);
            n++;
        }
        if (muted > 0) {
            log.info("审批 SLA 催办按偏好免打扰 todoNo={} 第{}轮 跳过{}人（已关闭审批类通知订阅）",
                    t.getTodoNo(), round, muted);
        }
        return n;
    }

    /** 催办目标人：assignee 非空仅催本人；否则按阶段角色路由（REVIEW 店长/REGION 区域经理/FINANCE 财务）。 */
    private Set<String> resolveRecipients(ApprovalTodo t) {
        Set<String> out = new LinkedHashSet<>();
        String assignee = t.getAssignee();
        if (assignee != null && !assignee.isBlank()) {
            out.add(assignee.trim());
            return out;
        }
        String stage = t.getStage();
        String store = t.getStoreCode();
        if ("FINANCE".equals(stage)) {
            orgStaffClient.listStaffByRole("FINANCE", null, null)
                    .forEach(s -> out.add(s.staffId()));
        } else if ("REGION".equals(stage)) {
            // 区域经理无门店归属：传 storeCode 由 org 侧解析门店所属区域再过滤
            orgStaffClient.listStaffByRole("REGION_MGR", store, null)
                    .forEach(s -> out.add(s.staffId()));
        } else {
            // REVIEW：店长按门店收敛；门店缺失时无法定位本店店长，本轮跳过（下轮自愈）
            if (store == null || store.isBlank()) {
                log.warn("审批 SLA REVIEW 阶段待办缺 storeCode，无法定位本店店长 todoNo={}", t.getTodoNo());
            } else {
                orgStaffClient.listStaffByRole("STORE_MGR", store, null)
                        .forEach(s -> out.add(s.staffId()));
            }
        }
        return out;
    }

    private long stageSlaHours(String stage) {
        return switch (stage == null ? "" : stage) {
            case "REGION" -> slaRegionHours;
            case "FINANCE" -> slaFinanceHours;
            default -> slaReviewHours;
        };
    }

    private static String stageLabel(String stage) {
        return switch (stage == null ? "" : stage) {
            case "REGION" -> "区域经理复审";
            case "FINANCE" -> "财务终审";
            default -> "店长一审";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
