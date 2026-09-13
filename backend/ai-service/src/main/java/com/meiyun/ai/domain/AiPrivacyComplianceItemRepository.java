package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiPrivacyComplianceItemRepository extends JpaRepository<AiPrivacyComplianceItem, Long> {

    List<AiPrivacyComplianceItem> findAllByOrderByItemIdAsc();

    long countByCheckedTrue();
}
