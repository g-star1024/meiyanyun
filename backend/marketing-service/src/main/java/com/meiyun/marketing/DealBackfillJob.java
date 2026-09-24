package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 成交回写定时任务（P5-B92，AutoGrantJob 范式镜像）。
 *
 * <p>每 5 分钟一轮、启动后 45s 首跑；每节拍先 paid 段后 refund 段，两段独立事务、
 * 分段游标，段故障不推进对应游标、下轮按原窗口自愈（Spring 单线程调度器串行，
 * 不会自身重叠）。任一段有实际产出（归属插入/退款冲销）才落单条 SYSTEM 汇总审计
 *（bizType=DEAL_BACKFILL、action=RUN，payload 含双段拉取/落行/跳过计数），
 * actor 记 SYSTEM；无产出不落审计防噪音。
 */
@Component
public class DealBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(DealBackfillJob.class);
    private static final String ACTOR = "SYSTEM";

    private final DealBackfillService dealBackfillService;
    private final AuditRecorder audit;

    public DealBackfillJob(DealBackfillService dealBackfillService, AuditRecorder audit) {
        this.dealBackfillService = dealBackfillService;
        this.audit = audit;
    }

    @Scheduled(fixedDelay = 300_000L, initialDelay = 45_000L)
    public void run() {
        DealBackfillService.PaidResult paid = dealBackfillService.paidSegment();
        DealBackfillService.RefundResult refund = dealBackfillService.refundSegment();
        if (paid.inserted() > 0 || refund.refunded() > 0) {
            audit.record("DEAL_BACKFILL", "DEAL-BACKFILL", ACTOR, "RUN",
                    "{\"paidFetched\":" + paid.fetched() + ",\"paidInserted\":" + paid.inserted()
                            + ",\"paidSkipped\":" + paid.skipped()
                            + ",\"refundFetched\":" + refund.fetched()
                            + ",\"refunded\":" + refund.refunded() + "}");
            log.info("成交回写完成：paid 拉{} 落{} 跳过{}；refund 拉{} 冲销{}",
                    paid.fetched(), paid.inserted(), paid.skipped(), refund.fetched(), refund.refunded());
        }
        if (paid.error() != null) {
            log.warn("成交回写 paid 段异常（下轮重试，游标不推进）：{}", paid.error());
        }
        if (refund.error() != null) {
            log.warn("成交回写 refund 段异常（下轮重试，游标不推进）：{}", refund.error());
        }
    }
}
