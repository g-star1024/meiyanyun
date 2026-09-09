package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GrantRuleRepository extends JpaRepository<GrantRule, String> {

    List<GrantRule> findByStatusOrderByPriorityAsc(String status);

    /** 取当日规则号最大值（BizNoGenerator 序号源），无则返回 null。 */
    GrantRule findTopByRuleIdLikeOrderByRuleIdDesc(String ruleIdLike);
}
