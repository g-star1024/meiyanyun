package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消费满额自动发赠金定时任务（B37 卡2）。
 *
 * <p>范式复刻 customer 域 {@code AutoPointsJob}：每 5 分钟一轮、启动后 45s 首跑、
 * 异常兜底不中断调度（Spring 默认单线程调度器串行，不会自身重叠）。
 * 有实际发放才落单条 GRANT_ISSUE/AUTO_GRANT 汇总审计，actor 记为 SYSTEM；
 * 每笔发放自身的明细审计由 {@link GrantService#issueByRule} 同步落（action=RULE）。
 * 扫描返回 error（txn/客户域故障）时游标未推进，仅告警，下轮按原窗口自愈。
 */
@Component
public class AutoGrantJob {

    private static final Logger log = LoggerFactory.getLogger(AutoGrantJob.class);
    private static final String ACTOR = "SYSTEM";

    private final AutoGrantService autoGrantService;
    private final AuditRecorder audit;

    public AutoGrantJob(AutoGrantService autoGrantService, AuditRecorder audit) {
        this.autoGrantService = autoGrantService;
        this.audit = audit;
    }

    @Scheduled(fixedDelay = 300_000L, initialDelay = 45_000L)
    public void run() {
        AutoGrantService.ScanResult res = autoGrantService.scan();
        if (res.granted() > 0) {
            audit.record("GRANT_ISSUE", "AUTO-GRANT", ACTOR, "AUTO_GRANT",
                    "{\"scanned\":" + res.scanned() + ",\"granted\":" + res.granted() + "}");
        }
        if (res.error() != null) {
            log.warn("消费满额自动发赠金扫描异常（下轮重试，游标不推进）：{}", res.error());
        } else if (res.granted() > 0) {
            log.info("消费满额自动发赠金扫描完成：扫描{} 发放{}", res.scanned(), res.granted());
        }
    }
}
