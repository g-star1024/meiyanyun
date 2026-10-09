package com.meiyun.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F2 #21 outbox 哨兵定时任务：每小时整点（北京时区）扫一轮「超时仍 PENDING」台账。
 *
 * <p>仅为薄壳，扫描/重试/挂 DIFF/审计逻辑全部在 {@link OutboxSentinelService#runOnce()}；
 * 此处再兜一层 try/catch 防异常冒泡干扰调度线程（下轮自愈）。频率按小时（对账为日级，
 * 超时 T+N 天后给 outbox_retry 次每小时重试，超限即挂 DIFF 催人工调平，节奏敏捷）。
 *
 * <p>双栈（prod/seed）各自起实例、各扫各库物理隔离；调度已在
 * {@link FinanceApplication} 以 @EnableScheduling 开启，cron 可被配置覆盖。
 */
@Component
public class OutboxSentinelJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxSentinelJob.class);

    private final OutboxSentinelService service;

    public OutboxSentinelJob(OutboxSentinelService service) {
        this.service = service;
    }

    @Scheduled(cron = "${meiyun.outbox-sentinel.cron:0 0 * * * ?}", zone = "Asia/Shanghai")
    public void hourlyScan() {
        try {
            OutboxSentinelService.ScanResult r = service.runOnce();
            log.info("outbox 哨兵定时扫描结束 超时PENDING={} 重试={} 挂DIFF={}",
                    r.scanned(), r.retried(), r.markedDiff());
        } catch (Exception e) {
            log.error("outbox 哨兵定时扫描失败（下轮自愈）: {}", e.getMessage(), e);
        }
    }
}
