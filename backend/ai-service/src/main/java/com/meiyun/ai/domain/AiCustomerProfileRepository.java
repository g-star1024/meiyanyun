package com.meiyun.ai.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiCustomerProfileRepository extends JpaRepository<AiCustomerProfile, Long> {

    /** 某客户最近一次画像（页面默认回显当前画像）。 */
    Optional<AiCustomerProfile> findFirstByCustomerIdOrderByProfileIdDesc(String customerId);

    Page<AiCustomerProfile> findByCustomerIdOrderByProfileIdDesc(String customerId, Pageable pageable);

    Page<AiCustomerProfile> findAllByOrderByProfileIdDesc(Pageable pageable);

    long countByAppliedToSegmentTrue();
}
