package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 订单（M4-10 开单 / M4-13 订单确认）。禁忌核验四态 contra_check。
 */
@Entity
@Table(name = "txn_order")
@Getter @Setter @NoArgsConstructor
public class TxnOrder {

    @Id
    @Column(name = "order_no", length = 24)
    private String orderNo;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(nullable = false, length = 64)
    private String project;

    @Column(nullable = false)
    private Long amount;

    /** 折前应收合计（分，B62 卡2 会员等级折扣前；无折扣历史行为空）。 */
    @Column(name = "original_amount")
    private Long originalAmount;

    /** 整单优惠金额（分）= originalAmount − amount；无折扣为空。 */
    @Column(name = "discount_amount")
    private Long discountAmount;

    /** 成交时会员等级中文快照（普通/银卡/金卡/钻石/黑卡）。 */
    @Column(name = "member_level", length = 8)
    private String memberLevel;

    /** 成交时会员等级英文 tier 快照（NORMAL/SILVER/GOLD/DIAMOND/BLACK）。 */
    @Column(name = "member_tier", length = 16)
    private String memberTier;

    /** 成交时折扣率快照（1.00=无折扣；0.95=95 折）。 */
    @Column(name = "member_discount", precision = 4, scale = 2)
    private BigDecimal memberDiscount;

    @Column(length = 32)
    private String consultant;

    @Column(name = "contra_check", nullable = false, length = 6)
    private String contraCheck;               // GREEN/YELLOW/RED

    @Column(name = "contra_detail", columnDefinition = "TEXT")
    private String contraDetail;

    @Column(name = "exemption_sign1", length = 32)
    private String exemptionSign1;

    @Column(name = "exemption_sign2", length = 32)
    private String exemptionSign2;

    @Column(length = 32)
    private String sign1;

    @Column(length = 32)
    private String sign2;

    @Column(nullable = false, length = 8)
    private String status;                    // 待签核/待收款/已收款/已核销/已取消

    /**
     * 业务种类（B16 售卡）：CARD_SALE=售卡/开卡单（收款收齐同事务回调 customer 开卡、
     * 资金走 RF-DEPOSIT/IN 预收、禁止储值余额支付）；普通诊疗/零售单为 null。
     */
    @Column(name = "biz_kind", length = 16)
    private String bizKind;

    /** 售卡模板编码快照（CD-/CS-），开卡回调溯源用；非售卡单为 null。 */
    @Column(name = "product_code", length = 32)
    private String productCode;

    /** 卡类型快照（CARD 储值卡 / COURSE 疗程卡），取自 catalog_product.product_type。 */
    @Column(name = "card_type", length = 16)
    private String cardType;

    /** 卡总次数快照（储值卡=1），取自 catalog_product.sessions。 */
    @Column(name = "card_total_times")
    private Integer cardTotalTimes;

    /** 有效期天数快照，取自 catalog_product.validity_days（0/空=长期有效）。 */
    @Column(name = "card_validity_days")
    private Integer cardValidityDays;

    /** 整单核销时刻（已收款订单核销回写；未核销为空）。 */
    @Column(name = "writeoff_at")
    private OffsetDateTime writeoffAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (contraCheck == null) contraCheck = "GREEN";
        if (status == null) status = "待签核";
    }
}
