package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiT4AlertRuleRepository extends JpaRepository<AiT4AlertRule, Long> {

    Optional<AiT4AlertRule> findByCode(String code);

    boolean existsByCode(String code);

    /** uk(name) 冲突预检（创建前给中文 400，避免 DB 约束 500） */
    boolean existsByName(String name);

    /** 规则列表全量直返（量级小，前端 rules 为数组契约，不分页） */
    List<AiT4AlertRule> findAllByOrderByRuleIdAsc();
}
