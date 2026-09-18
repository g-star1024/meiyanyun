package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 会员等级变更历史（域①-B62 卡1，append-only）。
 *
 * <p>所有调级路径在 service 单点留痕：{@link #SOURCE_LEVEL_INIT} 基线补种（from_level=NULL，
 * 时间取客户建档时间）、{@link #SOURCE_AUTO_UPGRADE}、{@link #SOURCE_AUTO_DOWNGRADE}、
 * {@link #SOURCE_MANUAL} 手工调级。降级保护期自最近 to_level=当前等级的进入月份起算。不建物理 FK。
 */
@Entity
@Table(name = "customer_level_history")
@Getter @Setter @NoArgsConstructor
public class CustomerLevelHistory {

    public static final String SOURCE_LEVEL_INIT = "LEVEL_INIT";
    public static final String SOURCE_AUTO_UPGRADE = "AUTO_UPGRADE";
    public static final String SOURCE_AUTO_DOWNGRADE = "AUTO_DOWNGRADE";
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 客户编号（逻辑引用不建物理 FK）。 */
    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    /** 原等级中文名（LEVEL_INIT 基线行为 NULL）。 */
    @Column(name = "from_level", length = 8)
    private String fromLevel;

    /** 新等级中文名（member_level.level）。 */
    @Column(name = "to_level", nullable = false, length = 8)
    private String toLevel;

    /** LEVEL_INIT/AUTO_UPGRADE/AUTO_DOWNGRADE/MANUAL。 */
    @Column(name = "change_source", nullable = false, length = 16)
    private String changeSource;

    /** 变更原因/审计摘要（≤64 字，对齐手工调级原因约束）。 */
    @Column(name = "reason", length = 64)
    private String reason;

    /** 变更生效时间。 */
    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;
}
