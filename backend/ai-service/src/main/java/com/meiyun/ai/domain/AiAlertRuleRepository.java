package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiAlertRuleRepository extends JpaRepository<AiAlertRule, Long> {

    List<AiAlertRule> findAllByOrderByRuleIdAsc();

    List<AiAlertRule> findByEnabledTrue();
}
