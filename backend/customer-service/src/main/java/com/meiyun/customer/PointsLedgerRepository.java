package com.meiyun.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PointsLedgerRepository extends JpaRepository<PointsLedger, Long> {
    /** 客户流水账龄正序（360 页全量展示用）。 */
    java.util.List<PointsLedger> findByCustomerIdOrderByLedgerIdAsc(String customerId);

    /** 客户流水分页（账龄倒序，最近一笔在最上）。 */
    Page<PointsLedger> findByCustomerIdOrderByLedgerIdDesc(String customerId, Pageable pageable);

    /** 人工调分幂等：同 clientToken 重放返回既有流水。 */
    Optional<PointsLedger> findByClientToken(String clientToken);
}
