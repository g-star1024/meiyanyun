package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointsLedgerRepository extends JpaRepository<PointsLedger, Long> {
    List<PointsLedger> findByCustomerIdOrderByLedgerIdAsc(String customerId);
}
