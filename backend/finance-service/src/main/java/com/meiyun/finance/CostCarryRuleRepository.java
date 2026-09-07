package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CostCarryRuleRepository extends JpaRepository<CostCarryRule, String> {

    List<CostCarryRule> findAllByOrderByRuleIdAsc();

    List<CostCarryRule> findByEnabledTrueOrderByRuleIdAsc();

    /**
     * 规则号当日最大序号（rule_id 形如 CCR20260906-000001：3 位前缀 + 8 位日期 + 1 个连字符，
     * 序号从第 13 位起 6 位）。
     */
    @Query(value = "select coalesce(max(cast(substring(rule_id from 13) as bigint)), 0) " +
           "from cost_carry_rule where rule_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
