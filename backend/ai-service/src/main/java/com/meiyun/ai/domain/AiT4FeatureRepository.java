package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiT4FeatureRepository extends JpaRepository<AiT4Feature, Long> {

    Optional<AiT4Feature> findByCode(String code);

    boolean existsByCode(String code);

    /** uk(name) 冲突预检（注册前给中文 400，避免 DB 约束 500） */
    boolean existsByName(String name);

    /** 特征列表全量直返（量级小，前端 features 为数组契约，不分页） */
    List<AiT4Feature> findAllByOrderByFeatureIdAsc();
}
