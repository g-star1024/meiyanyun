package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiFeatureBindingRepository extends JpaRepository<AiFeatureBinding, Long> {
    Optional<AiFeatureBinding> findByFeatureCode(String featureCode);

    boolean existsByModelId(Long modelId);

    List<AiFeatureBinding> findAllByOrderByBindingIdAsc();
}
