package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 月消费聚合游标（域①-B62 卡1）。
 *
 * <p>单行（state_id=1）记录已聚合的最近闭合自然月 {@code last_closed_month}（北京时区）。
 * 游标只推进到当前月的上一个已闭合月，在跑月绝不聚合；拉取交易域失败则不推进，下次断点续跑。
 * NULL=从未运行，首次从 2025-01 逐月回填。
 */
@Entity
@Table(name = "monthly_spend_state")
@Getter @Setter @NoArgsConstructor
public class MonthlySpendState {

    @Id
    @Column(name = "state_id")
    private Integer stateId = 1;

    /** 已聚合的最近闭合自然月（CHAR(7) 形如 2026-08）。NULL=从未运行（首次从 2025-01 回填）。 */
    @Column(name = "last_closed_month", length = 7)
    private String lastClosedMonth;

    /** 上次成功运行时间。 */
    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    /** 上次运行拉取的已收款单数。 */
    @Column(name = "last_paid_orders")
    private Long lastPaidOrders;

    /** 上次运行拉取的已退款单数。 */
    @Column(name = "last_refund_count")
    private Long lastRefundCount;

    /** 上次运行 upsert 的事实行数。 */
    @Column(name = "last_upsert_rows")
    private Long lastUpsertRows;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PreUpdate
    @PrePersist
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
