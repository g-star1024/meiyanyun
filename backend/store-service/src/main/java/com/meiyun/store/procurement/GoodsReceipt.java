package com.meiyun.store.procurement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 采购收货批次（B49 卡5）。一次「入库登记」落一行：
 * 同事务逐 SKU 调 {@code ConsumableService.stockIn}，库存入库批次号 = {@code receiptNo + "-" + lineNo}
 * （如 PO20260914-000001-R01-1），确定性幂等——网络重试不重复入库。
 * amount_fen 为本次各 SKU 收货数量×采购单价合计。
 */
@Entity
@Table(name = "goods_receipt",
        uniqueConstraints = @UniqueConstraint(name = "uk_receipt_no", columnNames = {"receipt_no"}),
        indexes = @Index(name = "idx_receipt_po", columnList = "po_id"))
@Getter
@Setter
@NoArgsConstructor
public class GoodsReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 收货批次号 PO号-Rxx（同单收货序号两位数） */
    @Column(name = "receipt_no", nullable = false, length = 48)
    private String receiptNo;

    @Column(name = "po_id", nullable = false)
    private Long poId;

    @Column(name = "po_no", nullable = false, length = 32)
    private String poNo;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 本次收货总数量（各 SKU 数量之和） */
    @Column(name = "total_qty", nullable = false)
    private int totalQty;

    /** 本次收货金额合计（分） */
    @Column(name = "amount_fen", nullable = false)
    private long amountFen;

    @Column(nullable = false, length = 64)
    private String receiver;

    @Column(length = 255)
    private String note;

    @Column(name = "received_at", nullable = false)
    private java.time.OffsetDateTime receivedAt;

    @PrePersist
    void prePersist() {
        if (receivedAt == null) receivedAt = java.time.OffsetDateTime.now();
    }
}
