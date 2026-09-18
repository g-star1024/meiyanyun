package com.meiyun.customer;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 月消费事实逐月落库器（域①-B62 卡1）。
 *
 * <p>独立 Bean 承载 {@link Transactional} 边界，保证经 Spring 代理生效（避免同类自调用导致事务失效）：
 * HTTP 拉取在 {@link MonthlySpendService} 事务外完成，单月全部事实 upsert 与游标推进在本方法一个事务内提交，
 * 月失败整体回滚、游标停在上一成功月。upsert 覆盖重算天然幂等，重跑不产生重复事实行。
 */
@Component
public class MonthlySpendWriter {

    private final CustomerMonthlySpendRepository spendRepo;
    private final MonthlySpendStateRepository stateRepo;

    public MonthlySpendWriter(CustomerMonthlySpendRepository spendRepo,
                              MonthlySpendStateRepository stateRepo) {
        this.spendRepo = spendRepo;
        this.stateRepo = stateRepo;
    }

    /**
     * 覆盖写入单月事实并推进游标。仅落主档仍存在的客户（known）；空客户/已删客户的金额在调用方已剔除。
     *
     * @return 本月 upsert 的事实行数
     */
    @Transactional
    public int upsertMonth(String period, Map<String, Long> paidSum, Map<String, Long> refundSum,
                           Map<String, Customer> known, MonthlySpendState state,
                           long paidOrders, long refundCount) {
        int rows = 0;
        for (Map.Entry<String, Customer> e : known.entrySet()) {
            String cid = e.getKey();
            long paid = paidSum.getOrDefault(cid, 0L);
            long refund = refundSum.getOrDefault(cid, 0L);
            if (paid <= 0 && refund <= 0) continue;
            CustomerMonthlySpend row = spendRepo.findByCustomerIdAndPeriodMonth(cid, period)
                    .orElseGet(CustomerMonthlySpend::new);
            row.setCustomerId(cid);
            row.setPeriodMonth(period);
            row.setPaidFen(paid);
            row.setRefundFen(refund);
            row.setNetFen(paid - refund);
            row.setStoreCode(e.getValue().getStoreCode());
            spendRepo.save(row);
            rows++;
        }
        state.setLastClosedMonth(period);
        state.setLastRunAt(OffsetDateTime.now());
        state.setLastPaidOrders(paidOrders);
        state.setLastRefundCount(refundCount);
        state.setLastUpsertRows((long) rows);
        stateRepo.save(state);
        return rows;
    }
}
