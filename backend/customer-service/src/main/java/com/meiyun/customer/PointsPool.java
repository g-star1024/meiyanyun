package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 积分池聚合统计表（单行，864万积分；pool_id 恒为 1）。
 * 对齐 prepay_pool 的单行聚合模式（pool_id PRIMARY KEY DEFAULT 1）。
 */
@Entity
@Table(name = "points_pool")
@Getter @Setter @NoArgsConstructor
public class PointsPool {

    @Id
    @Column(name = "pool_id")
    private Integer poolId;

    @Column(name = "total_issued", nullable = false)
    private Long totalIssued;

    @Column(name = "gained_month", nullable = false)
    private Long gainedMonth;

    @Column(name = "redeemed_month", nullable = false)
    private Long redeemedMonth;

    @Column(name = "expiring_90d", nullable = false)
    private Long expiring90d;
}
