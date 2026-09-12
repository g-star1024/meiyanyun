package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiModelRepository extends JpaRepository<AiModel, Long> {
    List<AiModel> findAllByOrderByPriorityAscModelIdDesc();
    List<AiModel> findByProviderIdOrderByModelIdDesc(Long providerId);
    Optional<AiModel> findByProviderIdAndModelCode(Long providerId, String modelCode);
}
