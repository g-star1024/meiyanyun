package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 方案单留痕仓储（consult_plan_revision），append-only。
 */
public interface PlanRevisionRepository extends JpaRepository<PlanRevision, Long> {

    List<PlanRevision> findByPlanIdOrderByRevIdAsc(String planId);

    List<PlanRevision> findByPlanIdInOrderByRevIdAsc(List<String> planIds);
}
