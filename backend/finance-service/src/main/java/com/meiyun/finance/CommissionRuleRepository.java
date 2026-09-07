package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CommissionRuleRepository extends JpaRepository<CommissionRule, String> {

    List<CommissionRule> findAllByOrderByCreatedAtDesc();

    List<CommissionRule> findByActiveTrueOrderByCreatedAtDesc();

    Optional<CommissionRule> findByRuleIdAndActiveTrue(String ruleId);

    /**
     * 提成规则号当日最大序号（rule_id 形如 CR20260906-000001：2 位前缀 + 8 位日期 + 1 个连字符，序号从第 12 位起 6 位）。
     */
    @Query(value = "select coalesce(max(cast(substring(rule_id from 12) as bigint)), 0) " +
           "from commission_rule where rule_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
