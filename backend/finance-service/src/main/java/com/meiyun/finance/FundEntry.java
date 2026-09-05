package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * fund_entry 资金分录（B3 合规写账本）。
 *
 * <p>经营域（txn）收款/退款终审/划扣完成后，经内部端点投递落账；金额 Long「分」始终为正，
 * 方向由 direction（IN/OUT）表达。idem_key 全局唯一保证重复投递幂等不双算。
 * 分录口径与 FinanceAggregationService 读时聚合逐字对齐：
 * 订单收款 RF-REVENUE/IN、退款 RF-REFUND/OUT、卡扣划扣 RF-DEPOSIT/OUT + RF-REVENUE/IN 成对。
 */
@Entity
@Table(name = "fund_entry")
@Getter @Setter @NoArgsConstructor
public class FundEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "idem_key", nullable = false, unique = true, length = 64)
    private String idemKey;

    @Column(name = "biz_ref", nullable = false, length = 48)
    private String bizRef;

    @Column(name = "biz_type", nullable = false, length = 16)
    private String bizType;

    @Column(nullable = false, length = 16)
    private String subject;

    @Column(nullable = false, length = 4)
    private String direction;

    @Column(nullable = false)
    private Long amount;

    @Column(length = 8)
    private String channel;

    @Column(nullable = false, length = 8)
    private String source;

    @Column(name = "ref_type", nullable = false, length = 16)
    private String refType;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(length = 128)
    private String memo;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
