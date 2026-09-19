package com.meiyun.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预收合规监控定时任务（B63 卡3 L85）。
 *
 * <p>每日 02:20（北京时区）触发一轮，与 customer 合规巡检 02:00 错峰。仅为薄壳，
 * 扫描/事件/告警/审计逻辑全部在 {@link PrepayMonitorService#runOnce()}；单规则异常已在
 * 服务内隔离，此处再兜一层 try/catch 防异常冒泡干扰调度线程。
 *
 * <p>双栈（prod/seed）各自起实例、各扫各库物理隔离，故不引 profile/条件 Bean；
 * 首启安全由「V38 零规则行 + 规则默认 enabled=false + 门店白名单」三层保证。
 */
@Component
public class PrepayMonitorJob {

    private static final Logger log = LoggerFactory.getLogger(PrepayMonitorJob.class);

    private final PrepayMonitorService service;

    public PrepayMonitorJob(PrepayMonitorService service) {
        this.service = service;
    }

    @Scheduled(cron = "${meiyun.security.prepay-monitor.cron:0 20 2 * * ?}", zone = "Asia/Shanghai")
    public void dailyScan() {
        try {
            PrepayMonitorService.ScanResult r = service.runOnce();
            log.info("预收合规监控定时扫描结束 rules={} stores={} fired={} alerts={} resolved={}",
                    r.rules(), r.stores(), r.eventsFired(), r.alertsSent(), r.resolved());
        } catch (Exception e) {
            log.error("预收合规监控定时扫描失败（下轮自愈）: {}", e.getMessage(), e);
        }
    }
}
