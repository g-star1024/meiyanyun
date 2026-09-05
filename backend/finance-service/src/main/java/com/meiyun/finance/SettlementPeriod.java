package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * settlement_period 封账期间锁（B7，DESIGN §9.2）。
 *
 * <p>封账维度：period_type（DAY 日结 / MONTH 月结）× period_key（DAY: yyyy-MM-dd；
 * MONTH: yyyy-MM，均按 UTC 归一，与 fund_entry.occurred_at 口径一致）× store_code 门店。
 * 存在一行 CLOSED 即表示该「期间 × 门店」已封账：此后任何 occurredAt 落入该期间的新分录
 * 在 {@link FundEntryService#postOne} 被 422 拒绝；封账永久、无解封，差错只能走当前期间 ADJUST。
 * entry_count / net_amount 为封账时点的分录笔数与净额快照（分，IN 正 OUT 负），随审计留存。
 */
@Entity
@Table(name = "settlement_period")
@Getter @Setter @NoArgsConstructor
public class SettlementPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "period_type", nullable = false, length = 8)
    private String periodType;

    @Column(name = "period_key", nullable = false, length = 10)
    private String periodKey;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(nullable = false, length = 8)
    private String status;

    @Column(name = "entry_count", nullable = false)
    private Integer entryCount;

    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    @Column(length = 128)
    private String memo;

    @Column(name = "closed_by", nullable = false, length = 64)
    private String closedBy;

    @Column(name = "closed_at", nullable = false)
    private OffsetDateTime closedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
