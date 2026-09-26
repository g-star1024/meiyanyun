package com.meiyun.txn;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 咨询方案单仓储（consult_plan）。
 */
public interface PlanRepository extends JpaRepository<ConsultPlan, String>, JpaSpecificationExecutor<ConsultPlan> {

    List<ConsultPlan> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    /** 按缴费单号查方案单（收款后回写 PAID 联动用）。 */
    java.util.Optional<ConsultPlan> findByOrderNo(String orderNo);

    List<ConsultPlan> findByStatusOrderByCreatedAtDesc(String status);

    List<ConsultPlan> findByStoreCodeAndStatusOrderByCreatedAtDesc(String storeCode, String status);

    List<ConsultPlan> findByStoreCodeOrderByCreatedAtDesc(String storeCode);

    Page<ConsultPlan> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<ConsultPlan> findByStoreCodeAndStatusOrderByCreatedAtDesc(String storeCode, String status, Pageable pageable);

    Page<ConsultPlan> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ConsultPlan> findByStoreCodeOrderByCreatedAtDesc(String storeCode, Pageable pageable);

    /** 生成当日不重号方案单号：CP + yyyyMMdd + - + 6 位序号（序号从第 12 位起）。 */
    @Query(value = "select coalesce(max(cast(substring(plan_id from 12) as bigint)),0) "
            + "from consult_plan where plan_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);

    @Query("SELECT p.storeCode, COUNT(p) FROM ConsultPlan p " +
            "WHERE p.createdAt >= :from AND p.createdAt < :to AND p.status <> 'ABANDONED' " +
            "GROUP BY p.storeCode")
    List<Object[]> funnelConsults(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Query("SELECT p.storeCode, COUNT(p) FROM ConsultPlan p " +
            "WHERE p.createdAt >= :from AND p.createdAt < :to " +
            "AND p.status IN ('PAID','TREATING','DONE') " +
            "GROUP BY p.storeCode")
    List<Object[]> funnelDeals(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** B99 转化漏斗客户级重写：区间方案行（customerId, arrivalId, storeCode），供 arrival_id 直链渠道归属内存 join。 */
    @Query("SELECT p.customerId, p.arrivalId, p.storeCode FROM ConsultPlan p " +
            "WHERE p.createdAt >= :from AND p.createdAt < :to AND p.status <> 'ABANDONED'")
    List<Object[]> funnelConsultRows(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** B99 咨询师排行：区间方案聚合（consultantId, 方案数, 成交数, 成交方案额合计-单位分）。 */
    @Query("SELECT p.consultantId, COUNT(p), " +
            "SUM(CASE WHEN p.status IN ('PAID','TREATING','DONE') THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN p.status IN ('PAID','TREATING','DONE') THEN COALESCE(p.planAmount, 0) ELSE 0 END) " +
            "FROM ConsultPlan p WHERE p.createdAt >= :from AND p.createdAt < :to " +
            "AND p.consultantId IS NOT NULL GROUP BY p.consultantId")
    List<Object[]> funnelConsultantRank(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
