package com.meiyun.store.procurement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 采购订单主表（B49 卡5）。六态状态机：
 * DRAFT 草稿 → SUBMITTED 待审批 → APPROVED 待入库 → PARTIAL 部分入库 → RECEIVED 已入库；
 * SUBMITTED 可驳回回 DRAFT；DRAFT/SUBMITTED/APPROVED/PARTIAL 可作废为 CANCELLED。
 * 金额一律 bigint 存「分」；sign_tier 提交时按总额定格（STORE/REGION/GROUP）。
 */
@Entity
@Table(name = "purchase_order",
        uniqueConstraints = @UniqueConstraint(name = "uk_po_no", columnNames = {"po_no"}),
        indexes = {
                @Index(name = "idx_po_store", columnList = "store_code"),
                @Index(name = "idx_po_status", columnList = "status"),
                @Index(name = "idx_po_supplier", columnList = "supplier_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 采购单号 POyyyyMMdd-000001（PoNoGenerator 防重号） */
    @Column(name = "po_no", nullable = false, length = 32)
    private String poNo;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    /** 收货门店码 */
    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** DRAFT / SUBMITTED / APPROVED / PARTIAL / RECEIVED / CANCELLED */
    @Column(nullable = false, length = 16)
    private String status;

    /** 采购总额（分），提交时按明细汇总定格 */
    @Column(name = "total_fen", nullable = false)
    private long totalFen;

    /** 审批层级：STORE / REGION / GROUP（提交时按总额分档定格） */
    @Column(name = "sign_tier", nullable = false, length = 16)
    private String signTier;

    @Column(name = "expect_date", length = 16)
    private String expectDate;

    @Column(length = 255)
    private String remark;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "submitted_at")
    private java.time.OffsetDateTime submittedAt;

    @Column(name = "approver", length = 64)
    private String approver;

    @Column(name = "approved_at")
    private java.time.OffsetDateTime approvedAt;

    /** 驳回/作废原因（审批意见等） */
    @Column(name = "reject_note", length = 255)
    private String rejectNote;

    @Column(name = "cancelled_at")
    private java.time.OffsetDateTime cancelledAt;

    @Column(name = "created_at", nullable = false)
    private java.time.OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = java.time.OffsetDateTime.now();
    }
}
