package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CustomerMonthlySpendRepository extends JpaRepository<CustomerMonthlySpend, Long> {

    Optional<CustomerMonthlySpend> findByCustomerIdAndPeriodMonth(String customerId, String periodMonth);

    List<CustomerMonthlySpend> findByCustomerIdAndPeriodMonthIn(String customerId, Collection<String> periodMonths);

    List<CustomerMonthlySpend> findByPeriodMonth(String periodMonth);

    /** 降级批处理：一次取多客户在 M/M-1/M-2 三个闭合月的事实行（缺行由调用方按 net=0 处理）。 */
    List<CustomerMonthlySpend> findByCustomerIdInAndPeriodMonthIn(Collection<String> customerIds,
                                                                  Collection<String> periodMonths);

    /** 自动升级批处理：一次取多客户截至指定闭合月（含）的全部事实行，内存 group sum，避免 N+1。 */
    List<CustomerMonthlySpend> findByCustomerIdInAndPeriodMonthLessThanEqual(Collection<String> customerIds,
                                                                             String periodMonth);
}
