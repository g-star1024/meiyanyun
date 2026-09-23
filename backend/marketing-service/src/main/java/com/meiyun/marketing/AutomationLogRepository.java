package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AutomationLogRepository extends JpaRepository<AutomationLog, Long> {

    /** 幂等防重核心：idem_key={ruleNo}:{customerId}:{triggerDate} 查重即跳。 */
    boolean existsByIdemKey(String idemKey);

    List<AutomationLog> findByTriggerDateOrderByIdDesc(LocalDate triggerDate);

    List<AutomationLog> findByRuleNoOrderByIdDesc(String ruleNo);

    List<AutomationLog> findByRuleNoAndTriggerDateOrderByIdDesc(String ruleNo, LocalDate triggerDate);

    /** 无过滤查询兜底：防爆量限 200 条。 */
    List<AutomationLog> findTop200ByOrderByIdDesc();
}
