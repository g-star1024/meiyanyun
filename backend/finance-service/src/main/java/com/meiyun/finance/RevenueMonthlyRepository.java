package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface RevenueMonthlyRepository
        extends JpaRepository<RevenueMonthly, RevenueMonthly.RevenueMonthlyId>,
        JpaSpecificationExecutor<RevenueMonthly> {

    List<RevenueMonthly> findByPeriodMonthOrderByStoreCodeAsc(java.time.LocalDate periodMonth);

    List<RevenueMonthly> findByStoreCodeOrderByPeriodMonthAsc(String storeCode);
}
