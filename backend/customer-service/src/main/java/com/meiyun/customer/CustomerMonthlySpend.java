package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 客户月消费事实（域①-B62 卡1）。
 *
 * <p>按「客户 × 自然月（UTC 月窗）」唯一：MonthlySpendService 逐月从交易域内部端点拉取
 * 已收款（剔除 CARD_SALE 储值购卡）与已退款（退卡不入窗），{@code net_fen = paid_fen - refund_fen}（分）。
 * 升降级判定统一以本表为唯一事实真源；每月幂等 upsert，重算覆盖旧值。不建物理 FK。
 */
@Entity
@Table(name = "customer_monthly_spend",
        uniqueConstraints = @UniqueConstraint(name = "uq_customer_monthly_spend",
                columnNames = {"customer_id", "period_month"}))
@Getter @Setter @NoArgsConstructor
public class CustomerMonthlySpend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 客户编号（customer.customer_id，逻辑引用不建物理 FK）。 */
    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    /** 自然月，形如 2026-08（UTC 月窗）。 */
    @Column(name = "period_month", nullable = false, length = 7)
    private String periodMonth;

    /** 当月已收款合计（分，剔除 CARD_SALE 储值购卡）。 */
    @Column(name = "paid_fen", nullable = false)
    private Long paidFen = 0L;

    /** 当月已退款合计（分，退卡 txn_card_cancel 不入窗）。 */
    @Column(name = "refund_fen", nullable = false)
    private Long refundFen = 0L;

    /** 当月净消费 = paid - refund（分，可为负）。 */
    @Column(name = "net_fen", nullable = false)
    private Long netFen = 0L;

    /** 聚合时客户主档门店码快照（公海客户为空）。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PreUpdate
    @PrePersist
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
