package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ConsultationRepository extends JpaRepository<Consultation, String>, JpaSpecificationExecutor<Consultation> {
    List<Consultation> findByCustomerIdOrderByCreatedAtDesc(String customerId);
    Optional<Consultation> findTopByCustomerIdOrderByCreatedAtDesc(String customerId);
}
