package com.meiyun.txn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 术后 SOP 超期未回访巡检 + 升级通知（P5-B30）。
 *
 * <p>范式复刻 {@link ApprovalSlaJob}：{@code @Scheduled} 限批 50 条 FIFO、单条 try-catch、
 * 不加方法级 @Transactional（每条独立 save，单条失败不回滚整批）、SLF4J 日志。每分钟一轮：</p>
 * <ol>
 *   <li>扫 PENDING + SOP 节点（sop_batch_id 非空）+ plan_date 早于今日零点（业务时区 +8）+ 未升级；</li>
 *   <li>经 {@link FollowupSopEscalator} 置 escalated=true（只升级一次），并给本门店 STORE_MGR 落 FOLLOWUP/URGENT 通知；</li>
 *   <li>通知幂等 idemKey=SOP-ESC:{followupNo}:{staffId}，收件人显式关闭 FOLLOWUP 类别订阅则免打扰。</li>
 * </ol>
 * <p>org 不可用时目标人软降级为空（本轮只置升级标记、不落通知，下轮自愈补通知），不阻断主链路。
 * 手动「一键升级」复用同一 {@link FollowupSopEscalator}。</p>
 */
@Component
public class FollowupSopDueJob {

    private static final Logger log = LoggerFactory.getLogger(FollowupSopDueJob.class);
    private static final ZoneOffset BIZ_TZ = ZoneOffset.ofHours(8);

    private final FollowupRepository followupRepo;
    private final FollowupSopEscalator escalator;

    public FollowupSopDueJob(FollowupRepository followupRepo, FollowupSopEscalator escalator) {
        this.followupRepo = followupRepo;
        this.escalator = escalator;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 25_000L)
    public void scanOverdue() {
        LocalDate today = LocalDate.now(BIZ_TZ);
        List<Followup> overdue = followupRepo.findOverdueForEscalation(today, PageRequest.of(0, 50));
        if (overdue.isEmpty()) return;
        for (Followup f : overdue) {
            try {
                escalator.escalate(f);
                followupRepo.save(f);
            } catch (Exception ex) {
                log.warn("术后 SOP 超期升级兜底异常 followupNo={}: {}", f.getFollowupNo(), ex.getMessage());
            }
        }
    }
}
