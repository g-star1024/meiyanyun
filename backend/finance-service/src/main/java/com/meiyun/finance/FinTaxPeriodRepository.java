package com.meiyun.finance;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FinTaxPeriodRepository
        extends JpaRepository<FinTaxPeriod, Long>,
                JpaSpecificationExecutor<FinTaxPeriod> {

    /** 同类型同期间的非归档记录（部分唯一索引 uk_fin_tax_period_open 保证至多一条）。 */
    Optional<FinTaxPeriod> findFirstByPeriodTypeAndPeriodAndStatusNot(
            String periodType, String period, String status, Sort sort);

    /** 覆盖指定日期且未归档的期间（懒创建/当前期解析），按 id 倒序兜底。 */
    @Query("select p from FinTaxPeriod p where p.periodType = :periodType "
            + "and :day between p.periodStart and p.periodEnd and p.status <> 'CLOSED' "
            + "order by p.id desc")
    List<FinTaxPeriod> findOpenContaining(@Param("periodType") String periodType,
                                          @Param("day") LocalDate day);

    List<FinTaxPeriod> findByPeriodType(String periodType, Sort sort);
}
