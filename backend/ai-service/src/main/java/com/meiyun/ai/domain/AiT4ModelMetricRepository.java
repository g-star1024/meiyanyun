package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiT4ModelMetricRepository extends JpaRepository<AiT4ModelMetric, Long> {

    Optional<AiT4ModelMetric> findByModelCode(String modelCode);

    /** 指标快照全量直返（uk(model_code) 一对一，前端 metrics 为数组契约） */
    List<AiT4ModelMetric> findAllByOrderByMetricIdAsc();
}
