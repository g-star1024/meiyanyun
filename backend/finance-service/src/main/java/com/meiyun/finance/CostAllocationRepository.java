package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CostAllocationRepository
        extends JpaRepository<CostAllocation, Long>,
        JpaSpecificationExecutor<CostAllocation> {

    /** B11 重算本月：删除该月系统结转成本行（source_ref 形如 CCR 规则号:yyyy-MM:门店），可按门店收敛。 */
    @Modifying
    @Query(value = "delete from cost_allocation where period_month = :month " +
            "and source_ref like 'CCR%' " +
            "and (:storeCode = '' or store_code = :storeCode)", nativeQuery = true)
    int deleteSystemCarry(@Param("month") LocalDate month,
                          @Param("storeCode") String storeCode);

    /**
     * 人工成本单号用：查当日最大序号。
     * source_ref 形如 COST20260905-000001（COST 4 位 + yyyyMMdd 8 位 + '-' 1 位 = 13 位前缀，
     * 序号自第 14 位起 6 位数字）。
     */
    @Query(value = "select coalesce(max(cast(substring(source_ref from 14) as integer)), 0) " +
            "from cost_allocation where source_ref like :prefix", nativeQuery = true)
    int maxSeqOfDay(@Param("prefix") String prefix);

    List<CostAllocation> findByPeriodMonthOrderByStoreCodeAscCostIdAsc(LocalDate periodMonth);
}
