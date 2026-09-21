package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerMergeSnapshotRepository extends JpaRepository<CustomerMergeSnapshot, Long> {

    /** 审计/对账：取一单全部迁移明细快照。 */
    List<CustomerMergeSnapshot> findByMergeId(String mergeId);
}
