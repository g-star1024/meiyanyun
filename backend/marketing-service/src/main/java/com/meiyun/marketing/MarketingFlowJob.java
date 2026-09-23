package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 营销自动化 Flow 定时任务（P5-B90）。
 *
 * <p>范式复刻 {@link AutoGrantJob}：每 5 分钟一轮、启动后 60s 首跑（DESIGN §2.2 定）、
 * 异常兜底不中断调度（Spring 默认单线程调度器串行，不会自身重叠）。
 * 有实际产物（created>0）才落单条 MARKETING_FLOW/FLOW_SCAN 汇总审计，actor 记为 SYSTEM；
 * 候选级执行明细（SUCCESS/SKIPPED/FAILED）由 {@link MarketingFlowService} 落 automation_log。
 * 扫描返回 error 时仅告警，下轮自愈（规则级域故障未落 idem，天然可重试）。
 */
@Component
public class MarketingFlowJob {

    private static final Logger log = LoggerFactory.getLogger(MarketingFlowJob.class);
    private static final String ACTOR = "SYSTEM";

    private final MarketingFlowService flowService;
    private final AuditRecorder audit;

    public MarketingFlowJob(MarketingFlowService flowService, AuditRecorder audit) {
        this.flowService = flowService;
        this.audit = audit;
    }

    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void run() {
        MarketingFlowService.ScanResult res = flowService.scan();
        if (res.created() > 0) {
            audit.record("MARKETING_FLOW", "FLOW-SCAN", ACTOR, "FLOW_SCAN",
                    "{\"scanned\":" + res.scanned() + ",\"created\":" + res.created()
                            + ",\"skipped\":" + res.skipped() + ",\"failed\":" + res.failed() + "}");
        }
        if (res.error() != null) {
            log.warn("营销 Flow 扫描异常（下轮自愈）：{}", res.error());
        } else if (res.created() > 0) {
            log.info("营销 Flow 扫描完成：扫描{} 生成{} 跳过{} 失败{}",
                    res.scanned(), res.created(), res.skipped(), res.failed());
        }
    }
}
