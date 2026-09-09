package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TagAutoRuleRepository extends JpaRepository<TagAutoRule, String> {

    /** 仅启用的规则，按优先级升序（小者先执行）。 */
    java.util.List<TagAutoRule> findByEnabledTrueOrderByPriorityAsc();

    /** 库内 TA### 最大编号（定长 3 位序号，字符串 max 与数值序一致）。 */
    @Query("select max(t.ruleId) from TagAutoRule t where t.ruleId like 'TA%'")
    String maxTaId();
}
