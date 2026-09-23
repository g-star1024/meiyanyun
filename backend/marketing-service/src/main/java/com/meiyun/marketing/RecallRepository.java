package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecallRepository extends JpaRepository<Recall, Long> {
    List<Recall> findAllByOrderByCreatedAtDesc();

    Optional<Recall> findByRecallNo(String recallNo);

    /** 单据号生成：取当日同前缀最大号（参数如 RC20260924-%）。 */
    Optional<Recall> findTopByRecallNoLikeOrderByRecallNoDesc(String prefix);

    /** Flow 引擎在途防重：同规则同客户存在未闭环（PENDING/NOTIFIED/CONFIRMED）召回单则不重复生成。 */
    boolean existsByRuleNoAndCustomerIdAndStatusIn(String ruleNo, String customerId, List<String> statuses);
}
