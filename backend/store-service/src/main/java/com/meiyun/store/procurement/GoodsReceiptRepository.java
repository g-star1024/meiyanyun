package com.meiyun.store.procurement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 采购收货批次仓库 */
public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {

    long countByPoId(Long poId);

    Optional<GoodsReceipt> findByReceiptNo(String receiptNo);

    List<GoodsReceipt> findByPoIdOrderByIdDesc(Long poId);
}
