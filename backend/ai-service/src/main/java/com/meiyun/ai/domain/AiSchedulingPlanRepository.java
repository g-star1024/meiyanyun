package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiSchedulingPlanRepository extends JpaRepository<AiSchedulingPlan, Long> {

    List<AiSchedulingPlan> findByWeekStartAndStoreCodeOrderByPlanIdDesc(String weekStart, String storeCode);

    long countByStatus(String status);

    List<AiSchedulingPlan> findTop60ByOrderByPlanIdDesc();
}
