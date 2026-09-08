package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 储值卡流水台账（append-only：只 INSERT，与 points_ledger/审计链同哲学）。
 *
 * <p>member_card.balance 为该卡流水的累计快照，对账恒等式：Σ card_ledger.amount = member_card.balance；
 * B18 起赠金同口径对账：Σ card_ledger.gift_amount = member_card.gift_balance（历史行 gift 两列为 NULL，视为 0）。
 * change_type：RECHARGE 充值（正）/ CONSUME 消费扣额（负）/ REFUND 退款回加（订单退款为正、退卡清零为负）/
 * ADJUST 调整（退卡冻结/解冻，amount=0、gift_amount=0，仅作状态留痕）。
 */
@Entity
@Table(name = "card_ledger")
@Getter @Setter @NoArgsConstructor
public class CardLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ledger_id")
    private Long ledgerId;

    @Column(name = "card_no", nullable = false, length = 24)
    private String cardNo;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    /** RECHARGE / CONSUME / REFUND / ADJUST。 */
    @Column(name = "change_type", nullable = false, length = 16)
    private String changeType;

    @Column(nullable = false)
    private Long amount;                      // 变动额（分）：充值正，消费/退卡负

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;                // 变动后卡本金余额（分，对账锚点）

    /** 赠金变动额（分，B18 加列）：充值发放为正、消费扣赠为负、退卡清零为负；冻结/解冻 ADJUST 行与历史行为 NULL（视为 0）。 */
    @Column(name = "gift_amount")
    private Long giftAmount;

    /** 变动后赠金余额（分，B18 加列，赠金对账锚点）；未涉及赠金的行（冻结/解冻、历史行）为 NULL。 */
    @Column(name = "gift_after")
    private Long giftAfter;

    /** 来源单号：充值 RC 单号 / 订单号 / 退卡 RF-CC 号 / 冻结 CC…-F / 解冻 CC…-U。 */
    @Column(name = "biz_ref", length = 24)
    private String bizRef;

    /** 关联订单号：储值消费扣款（CONSUME）与订单退款回加（REFUND 正额）按订单归集，供「累计回加 ≤ 原扣款」防超退；充值/退卡清零为空。 */
    @Column(name = "order_no", length = 24)
    private String orderNo;

    @Column(length = 24)
    private String operator;                  // 经办人（内部系统动账记 system）

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
