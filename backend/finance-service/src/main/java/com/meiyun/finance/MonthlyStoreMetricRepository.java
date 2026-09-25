package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.List;

public interface MonthlyStoreMetricRepository
        extends JpaRepository<MonthlyStoreMetric, MonthlyStoreMetric.MonthlyStoreMetricId>,
        JpaSpecificationExecutor<MonthlyStoreMetric> {

    List<MonthlyStoreMetric> findByPeriodMonthOrderByStoreCodeAsc(LocalDate periodMonth);

    List<MonthlyStoreMetric> findByStoreCodeInAndPeriodMonthBetween(
            List<String> storeCodes, LocalDate start, LocalDate end);
}
