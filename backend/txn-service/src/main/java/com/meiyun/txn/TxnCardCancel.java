package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 退卡流水（DDL §6，TK 前缀，独立序列）。
 * 手续费默认 10%；医疗禁忌确诊退卡默认免收(0)；提供手动配置窗口（操作人逐单覆盖扣否/扣多少）。
 * 约束：① 退卡金额 + 手续费 = 卡内余额（ID-8）② 比例约束仅在「非禁忌且非手动覆盖」时要求 = 10%。
 */
@Entity
@Table(name = "txn_card_cancel")
@Getter
@Setter
@NoArgsConstructor
public class TxnCardCancel {

    @Id
    @Column(name = "txn_no", length = 24)
    private String txnNo;            // CC2026xxxx-xxx（前端活规格；历史 TK 兼容）

    /** 关联会员卡/资产 ID（退卡来源）。 */
    @Column(name = "card_no", length = 24)
    private String cardNo;

    /** 数据域门店码（创建时按会员卡回填；数据权限过滤依据）。 */
    @Column(name = "store_code", length = 32)
    private String storeCode;

    /** 客户号（customer 列历史存姓名，新链路写客户号；customerName 冗余姓名展示）。 */
    @Column(length = 32)
    private String customer;

    @Column(name = "customer_name", length = 64)
    private String customerName;

    @Column(name = "card_item", length = 64)
    private String cardItem;

    /** 退款渠道：ORIGINAL / CASH / TRANSFER。 */
    @Column(length = 16)
    private String channel;

    @Column(nullable = false)
    private Long balance;            // 卡内余额（分）

    @Column(name = "refund_amt", nullable = false)
    private Long refundAmt;          // 默认 = balance×0.9（可手动覆盖）

    @Column(nullable = false)
    private Long fee;                // 默认 = balance×0.1；禁忌 0；可手动覆盖

    @Column(name = "fee_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal feeRate = BigDecimal.valueOf(0.1);
    @Column(name = "fee_manual_override", nullable = false)
    private boolean feeManualOverride = false;

    @Column(name = "fee_override_reason", length = 128)
    private String feeOverrideReason;

    @Column(name = "is_medical_contraindication", nullable = false)
    private boolean medicalContraindication = false;

    @Column(name = "remain_times")
    private Integer remainTimes;

    /** 审批状态机：PENDING_REVIEW → PENDING_FINANCE → REFUNDED；任一阶段可 REJECTED。 */
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'PENDING_REVIEW'")
    private String status = "PENDING_REVIEW";

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

    @Column(name = "sign_tier", nullable = false, length = 2)
    private String signTier;

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
