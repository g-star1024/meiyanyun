package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface RepurchaseRepository extends JpaRepository<Repurchase, String>, JpaSpecificationExecutor<Repurchase> {

    List<Repurchase> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<Repurchase> findAllByOrderByCreatedAtDesc();
}
