package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AutomationRuleRepository extends JpaRepository<AutomationRule, Long> {
    List<AutomationRule> findAllByOrderByCreatedAtDesc();

    /** Flow 引擎每轮扫描启用规则。 */
    List<AutomationRule> findByEnabledTrueOrderByCreatedAtDesc();

    Optional<AutomationRule> findByRuleNo(String ruleNo);

    /** 单据号生成：取当日同前缀最大号（参数如 AR20260924-%）。 */
    Optional<AutomationRule> findTopByRuleNoLikeOrderByRuleNoDesc(String prefix);
}
