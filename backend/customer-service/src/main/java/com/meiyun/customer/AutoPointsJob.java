package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消费自动积分定时任务（域①-258）。
 *
 * <p>范式复刻 txn-service {@code ApprovalSlaJob}：每 5 分钟一轮、单批扫描、异常兜底不中断主链路、SLF4J 日志。
 * 调用 {@link AutoPointsService#scan()} 拉取窗口内已收款/已退款订单，按积分规则自动发分与回退，
 * 幂等键保证重跑不重复加减分。有实际发/退分则落单条 POINTS/AUTO_POINTS 汇总审计，actor 记为 SYSTEM。
 */
@Component
public class AutoPointsJob {

    private static final Logger log = LoggerFactory.getLogger(AutoPointsJob.class);
    private static final String ACTOR = "SYSTEM";

    private final AutoPointsService pointsService;
    private final AuditRecorder audit;

    public AutoPointsJob(AutoPointsService pointsService, AuditRecorder audit) {
        this.pointsService = pointsService;
        this.audit = audit;
    }

    @Scheduled(fixedDelay = 300_000L, initialDelay = 45_000L)
    public void run() {
        AutoPointsService.ScanResult res = pointsService.scan();
        if (res.awardedOrders() > 0 || res.refundedOrders() > 0) {
            audit.record("POINTS", "AUTO-POINTS", ACTOR, "AUTO_POINTS",
                    "{\"scanned\":" + res.scanned() + ",\"awardedOrders\":" + res.awardedOrders()
                            + ",\"awardedPoints\":" + res.awardedPoints()
                            + ",\"refundedOrders\":" + res.refundedOrders()
                            + ",\"refundedPoints\":" + res.refundedPoints() + "}");
        }
        if (res.error() != null) {
            log.warn("消费自动积分扫描异常（下轮重试，游标不推进）：{}", res.error());
        } else {
            log.info("消费自动积分扫描完成：扫描{} 发分订单{} 发分{} 退分订单{} 退分{}",
                    res.scanned(), res.awardedOrders(), res.awardedPoints(),
                    res.refundedOrders(), res.refundedPoints());
        }
    }
}
