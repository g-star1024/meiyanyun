package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 退款流水（DDL §6，RF 前缀，独立序列）。
 * 退款手续费默认 0；提供手动配置窗口（扣否/扣多少）。
 */
@Entity
@Table(name = "txn_refund")
@Getter
@Setter
@NoArgsConstructor
public class TxnRefund {

    @Id
    @Column(name = "txn_no", length = 24)
    private String txnNo;            // RF2026xxxx-xxx

    /** 关联订单号（订单退款来源）。 */
    @Column(name = "order_no", length = 24)
    private String orderNo;

    /** 数据域门店码（创建时按订单回填，找不到订单取登录人本店；数据权限过滤依据）。 */
    @Column(name = "store_code", length = 32)
    private String storeCode;

    /** 客户号（customer 列历史存姓名，新链路写客户号；customerName 冗余姓名展示）。 */
    @Column(length = 32)
    private String customer;

    @Column(name = "customer_name", length = 64)
    private String customerName;

    @Column(length = 64)
    private String project;

    /** 退款渠道：ORIGINAL=原路退回 / CASH=现金 / TRANSFER=转账。 */
    @Column(length = 16)
    private String channel;

    /** 已付金额（分），退款不得超过该金额。 */
    @Column(name = "paid_amt")
    private Long paidAmt;

    @Column(name = "refund_amt", nullable = false)
    private Long refundAmt;          // 分

    @Column(length = 64)
    private String reason;

    /** 审批状态机：PENDING_REVIEW（待店长/运营审核）→ PENDING_FINANCE（待财务复核）→ REFUNDED（已退款）；任一阶段可 REJECTED。 */
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'PENDING_REVIEW'")
    private String status = "PENDING_REVIEW";

    /** 发起人（员工工号）。 */
    @Column(name = "applicant", length = 32)
    private String applicant;

    @Column(name = "reviewed_by", length = 32)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "finance_by", length = 32)
    private String financeBy;

    @Column(name = "refunded_at")
    private OffsetDateTime refundedAt;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    @Column(name = "rejected_by", length = 32)
    private String rejectedBy;

    @Column(nullable = false)
    private Long fee = 0L;           // 手续费（默认 0；手动窗口可扣）

    @Column(name = "fee_manual_override", nullable = false)
    private boolean feeManualOverride = false;

    @Column(name = "fee_override_reason", length = 128)
    private String feeOverrideReason;

    @Column(name = "sign_tier", nullable = false, length = 2)
    private String signTier;         // L1/L2/L3（退款属出账类，L1 抬升 L2）

    @Column(length = 32)
    private String sign1;

    @Column(length = 32)
    private String sign2;

    @Column(name = "signed_at1")
    private OffsetDateTime signedAt1;

    @Column(name = "signed_at2")
    private OffsetDateTime signedAt2;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
