package com.meiyun.customer;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataGovernIssueRepository extends JpaRepository<DataGovernIssue, Long> {

    /** 问题单列表按 id 升序（种子插入序=前端 mock 数组序）。 */
    List<DataGovernIssue> findAllByOrderByIdAsc();

    /** 规则当前 OPEN 问题单（runRule 幂等检出：已有 OPEN 单则刷新样本/条数，不重复开单）。 */
    Optional<DataGovernIssue> findFirstByRuleIdAndStatusOrderByIdDesc(Long ruleId, String status);
}
