package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiPrivacyMaskRuleRepository extends JpaRepository<AiPrivacyMaskRule, Long> {

    List<AiPrivacyMaskRule> findAllByOrderByRuleIdAsc();

    long countByEnabledFalse();
}
