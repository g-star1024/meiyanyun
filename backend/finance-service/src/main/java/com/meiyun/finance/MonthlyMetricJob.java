package com.meiyun.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * T2 事实表月游标调度（P5-B97，DESIGN-T2 §3 D5）：
 * 每月 1 日 02:30（Asia/Shanghai）结算上月；每日 03:30 刷新当月。
 * 薄壳——逻辑全在 MetricMonthlyService；单轮异常仅记日志，下轮自愈（cron 占位符可外部覆盖）。
 */
@Component
public class MonthlyMetricJob {

    private static final Logger log = LoggerFactory.getLogger(MonthlyMetricJob.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MetricMonthlyService metricMonthlyService;

    public MonthlyMetricJob(MetricMonthlyService metricMonthlyService) {
        this.metricMonthlyService = metricMonthlyService;
    }

    /** 每月 1 日 02:30 结算上月（默认 cron：0 30 2 1 * ?，可由 meiyun.metric-monthly.cron 覆盖）。 */
    @Scheduled(cron = "${meiyun.metric-monthly.cron:0 30 2 1 * ?}", zone = "Asia/Shanghai")
    public void settleLastMonth() {
        LocalDate period = LocalDate.now(ZONE).minusMonths(1).withDayOfMonth(1);
        try {
            metricMonthlyService.settleMonth(period);
        } catch (Exception e) {
            log.error("月度事实表上月结算失败（下轮自愈） {}: {}", period, e.getMessage(), e);
        }
    }

    /** 每日 03:30 刷新当月（默认 cron：0 30 3 * * ?，可由 meiyun.metric-daily.cron 覆盖）。 */
    @Scheduled(cron = "${meiyun.metric-daily.cron:0 30 3 * * ?}", zone = "Asia/Shanghai")
    public void refreshCurrentMonth() {
        LocalDate period = LocalDate.now(ZONE).withDayOfMonth(1);
        try {
            metricMonthlyService.settleMonth(period);
        } catch (Exception e) {
            log.error("月度事实表当月刷新失败（下轮自愈） {}: {}", period, e.getMessage(), e);
        }
    }
}
