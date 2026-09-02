package com.meiyun.txn;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
