package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 标签自动化规则（域①客户域自动化 Backlog-257）。
 *
 * <p>由运营/门店在「客户标签 → 自动化规则」配置：当客户满足某条件时，自动打上（或撤销）指定标签。
 * 规则由 {@link TagAutoRuleJob} 定时扫描执行，按 priority 升序逐条生效；打标/撤标复用既有
 * {@link CustomerService#assignTag} / {@link CustomerService#unassignTag}，受复合主键唯一约束与
 * 幂等保护（已打标重复执行不报错、不重复落审计）。
 *
 * <p>条件语义（conditionType + conditionValue）：
 * <ul>
 *   <li>CONSUME_GTE：累计消费 ≥ conditionValue（元，BigDecimal 字符串）</li>
 *   <li>VISIT_GTE：到店次数 ≥ conditionValue（整数）</li>
 *   <li>POINTS_GTE：当前积分 ≥ conditionValue（整数）</li>
 *   <li>LEVEL_IN：会员等级命中 conditionValue 逗号清单之一（如 金卡,钻石）</li>
 *   <li>CHANNEL_EQ：获客渠道等于 conditionValue（如 WECHAT）</li>
 * </ul>
 *
 * <p>effect=ASSIGN 且 revokeWhenUnsatisfied=true 时，条件不满足则自动撤标（保持标签与现状一致）；
 * effect=REVOKE 用于「把曾经达标但现在不达标的客户移出某标签」。
 */
@Entity
@Table(name = "tag_auto_rule")
@Getter @Setter @NoArgsConstructor
public class TagAutoRule {

    @Id
    @Column(name = "rule_id", length = 16)
    private String ruleId;                 // TA###

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** ASSIGN=条件满足打标；REVOKE=条件满足撤标。 */
    @Column(name = "effect", nullable = false, length = 8)
    private String effect;

    /** 目标标签（customer_tag.tag_id）。 */
    @Column(name = "target_tag_id", nullable = false, length = 16)
    private String targetTagId;

    /** 条件类型：CONSUME_GTE / VISIT_GTE / POINTS_GTE / LEVEL_IN / CHANNEL_EQ。 */
    @Column(name = "condition_type", nullable = false, length = 16)
    private String conditionType;

    /** 条件值：数值字符串或逗号清单（见类注释语义）。 */
    @Column(name = "condition_value", length = 128)
    private String conditionValue;

    /** 执行优先级（升序：小者先执行）。 */
    @Column(nullable = false)
    private Integer priority = 100;

    /** effect=ASSIGN 时，条件不满足是否自动撤标（保持动态一致）。默认 false（保守：只增不删）。 */
    @Column(name = "revoke_when_unsatisfied")
    private Boolean revokeWhenUnsatisfied = false;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (updatedAt == null) updatedAt = createdAt;
        if (enabled == null) enabled = true;
        if (priority == null) priority = 100;
        if (revokeWhenUnsatisfied == null) revokeWhenUnsatisfied = false;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
