package com.meiyun.store.procurement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 采购单明细行仓库 */
public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    List<PurchaseOrderItem> findByPoIdOrderByLineNoAsc(Long poId);
}
