package com.meiyun.marketing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 消费满额自动发赠金游标（B37 卡2）。
 *
 * <p>单行（state_id=1）记录上次成功扫描的窗口上界 last_run_at，保证 {@link AutoGrantJob}
 * 断点续跑。首次运行为 NULL → 任务以远早于系统的下界兜底（全量回填已收款订单），
 * 由规则幂等键 RULE:{ruleId}:{orderNo} 与 customer_grant.uk_cg_idem 保证重放不重复发放；
 * 邻轮窗口按日粒度重叠，同一订单跨轮重复出现同样被幂等吞掉。
 *
 * <p>marketing 域无 Flyway，本表由 JPA {@code ddl-auto=update} 自动建立（与 grant_rule /
 * customer_grant 同策略），版本化 DDL 基线已在 ROADMAP 登记为中期治理。
 */
@Entity
@Table(name = "auto_grant_state")
@Getter
@Setter
@NoArgsConstructor
public class AutoGrantState {

    @Id
    @Column(name = "state_id")
    private Integer stateId = 1;

    /** 上次成功扫描窗口上界（created_at &lt; 该值的已收款订单已处理）。NULL=从未运行（首次全量回填）。 */
    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    /** 上次运行扫描的已收款订单数（仅统计用）。 */
    @Column(name = "last_scanned")
    private Long lastScanned;

    /** 上次运行实际新发放的赠金笔数。 */
    @Column(name = "last_granted")
    private Long lastGranted;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PreUpdate
    @PrePersist
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
