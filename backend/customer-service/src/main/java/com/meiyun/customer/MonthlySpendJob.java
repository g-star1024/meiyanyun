package com.meiyun.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 客户月消费事实聚合定时任务（域①-B62 卡1）。
 *
 * <p>范式复刻 {@link AutoPointsJob}：长间隔单批轮询、异常兜底不中断主链路、SLF4J 日志。
 * 调用 {@link MonthlySpendService#aggregateClosedMonths()} 把所有尚未入表的北京时区已闭合自然月
 * 逐月回填并推进游标；游标已追平最近闭合月时零请求空转。已闭合月数据不可变，正常轮次为零动作；
 * txn 不可用致某月失败时仅告警，下轮断点续跑。聚合本身不落审计（升降级动作的审计在卡1d/1e 调级环节）。
 */
@Component
public class MonthlySpendJob {

    private static final Logger log = LoggerFactory.getLogger(MonthlySpendJob.class);

    private final MonthlySpendService monthlySpendService;

    public MonthlySpendJob(MonthlySpendService monthlySpendService) {
        this.monthlySpendService = monthlySpendService;
    }

    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 75_000L)
    public void run() {
        MonthlySpendService.AggregateResult res = monthlySpendService.aggregateClosedMonths();
        if (res.error() != null) {
            log.warn("月消费聚合未完成（下轮断点续跑，游标不推进）：{}", res.error());
            return;
        }
        if (!res.months().isEmpty()) {
            log.info("月消费聚合完成：聚合月{} upsert事实行{} 已收款单{} 退款单{}",
                    res.months(), res.upsertRows(), res.paidOrders(), res.refundCount());
        }
    }
}
