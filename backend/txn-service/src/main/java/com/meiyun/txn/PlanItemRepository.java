package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 方案子项仓储（consult_plan_item）。
 */
public interface PlanItemRepository extends JpaRepository<PlanItem, Long> {

    List<PlanItem> findByPlanIdOrderByLineNoAsc(String planId);

    List<PlanItem> findByPlanIdInOrderByLineNoAsc(List<String> planIds);

    void deleteByPlanId(String planId);
}
