package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 自动积分游标（域①-258）。
 *
 * <p>单行（state_id=1）记录上次成功扫描的窗口上界 last_run_at，保证定时任务可断点续跑、
 * 不重复扫描历史订单。首次运行为 NULL → 任务以远早于系统的下界兜底（全量回填已收款订单）。
 */
@Entity
@Table(name = "auto_points_state")
@Getter @Setter @NoArgsConstructor
public class AutoPointsState {

    @Id
    @Column(name = "state_id")
    private Integer stateId = 1;

    /** 上次扫描窗口上界（已收款/已退款订单 created_at < 该值已处理）。NULL=从未运行（首次全量回填）。 */
    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    /** 上次运行扫描的订单数（含发放与回退，仅统计用）。 */
    @Column(name = "last_scanned")
    private Long lastScanned;

    /** 上次运行实际发放的积分数（正）。 */
    @Column(name = "last_awarded")
    private Long lastAwarded;

    /** 上次运行实际回退的积分数（负，绝对值记录）。 */
    @Column(name = "last_refunded_points")
    private Long lastRefundedPoints;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PreUpdate
    @PrePersist
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
