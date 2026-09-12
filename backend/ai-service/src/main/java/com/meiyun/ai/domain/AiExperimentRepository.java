package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiExperimentRepository extends JpaRepository<AiExperiment, Long> {
    List<AiExperiment> findAllByOrderByExperimentIdDesc();
    long countByStatus(String status);
}
