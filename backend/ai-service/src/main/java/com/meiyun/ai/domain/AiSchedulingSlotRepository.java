package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiSchedulingSlotRepository extends JpaRepository<AiSchedulingSlot, Long> {

    List<AiSchedulingSlot> findByPlanIdOrderBySlotIdAsc(Long planId);
}
