package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiEvalTaskRepository extends JpaRepository<AiEvalTask, Long> {
    List<AiEvalTask> findAllByOrderByTaskIdDesc();
    long countByStatus(String status);
}
