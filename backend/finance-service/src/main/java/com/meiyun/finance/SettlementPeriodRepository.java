package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface SettlementPeriodRepository extends JpaRepository<SettlementPeriod, Long>,
        JpaSpecificationExecutor<SettlementPeriod> {

    /** 封账幂等：同「期间类型 × 期间键 × 门店」唯一。 */
    Optional<SettlementPeriod> findByPeriodTypeAndPeriodKeyAndStoreCode(
            String periodType, String periodKey, String storeCode);

    /** 闭期判定（FundEntryService 每条新分录落账前调用）。 */
    boolean existsByPeriodTypeAndPeriodKeyAndStoreCode(
            String periodType, String periodKey, String storeCode);

    /** 月结封账判定（不限门店）：该月任一门店已封账即命中——结转重算删除是全门店范围，据此提前拦截。 */
    boolean existsByPeriodTypeAndPeriodKey(String periodType, String periodKey);

    List<SettlementPeriod> findByPeriodTypeAndPeriodKeyOrderByClosedAtDesc(
            String periodType, String periodKey);

    List<SettlementPeriod> findByPeriodTypeOrderByPeriodKeyDescClosedAtDesc(String periodType);

    List<SettlementPeriod> findAllByOrderByPeriodKeyDescClosedAtDesc();
}
