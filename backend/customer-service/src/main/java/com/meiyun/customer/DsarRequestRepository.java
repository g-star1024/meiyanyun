package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * DSAR 工单持久层。JPA ddl-auto=update 自动建表，无 Flyway 脚本（业务域表）。
 */
public interface DsarRequestRepository extends JpaRepository<DsarRequest, Long>, JpaSpecificationExecutor<DsarRequest> {

    /** 取号：查指定前缀（如 DSAR20260917%）的最大工单号，防重号；无则返回 null。 */
    @Query("select max(r.requestNo) from DsarRequest r where r.requestNo like :prefix")
    String maxRequestNoByPrefix(@Param("prefix") String prefix);

    /** 幂等校验：同 customer_id+type+SUBMITTED 视为重复请求（409）。 */
    boolean existsByCustomerIdAndTypeAndStatus(String customerId, String type, String status);

    /** 四态计数：按 status 分组，供 stats 端点用。 */
    @Query("select r.status, count(r) from DsarRequest r group by r.status")
    List<Object[]> countGroupByStatus();

    /** 超期计数：deadline_at < now 且状态未闭合（FULFILLED/REJECTED）。 */
    @Query("select count(r) from DsarRequest r where r.deadlineAt < :now and r.status not in :closed")
    long countOverdue(@Param("now") OffsetDateTime now, @Param("closed") List<String> closed);

    /**
     * 超期清单：deadline_at < 指定阈值且状态未闭合（供巡检 Job 用，WARN 取 now+7d / CRITICAL 取 now）。
     * 按 deadlineAt 升序（最紧急的在前），便于告警时优先展示。
     */
    @Query("select r from DsarRequest r where r.deadlineAt < :threshold and r.status not in :closed order by r.deadlineAt asc")
    List<DsarRequest> findOverdueBefore(@Param("threshold") OffsetDateTime threshold, @Param("closed") List<String> closed);
}
