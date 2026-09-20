package com.meiyun.store.requisition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequisitionItemRepository extends JpaRepository<RequisitionItem, Long> {

    List<RequisitionItem> findByRqIdOrderByLineNoAsc(Long rqId);
}
