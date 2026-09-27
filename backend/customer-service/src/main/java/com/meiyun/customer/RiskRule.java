package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 风控规则（M3-B6 / DESIGN-M3 §3 M3-17，表 risk_rule / V60）。
 * 规则定义＋启停开关＋累计命中；本期切真范围照 mock 活规格＝列表＋启停 toggle
 * （规则引擎真实命中计算在 txn/事件域后续批，hit_count 本期留存展示值）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "risk_rule")
public class RiskRule {

    /** 命中动作三值。 */
    public static final String ACTION_BLOCK = "BLOCK";
    public static final String ACTION_WARN = "WARN";
    public static final String ACTION_REVIEW = "REVIEW";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则编号：RR-数字（种子沿用 mock 字面值 RR-1..RR-5）。 */
    @Column(name = "rule_no", nullable = false, unique = true, length = 16)
    private String ruleNo;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "action", nullable = false, length = 8)
    private String action;

    @Column(name = "hit_count", nullable = false)
    private Integer hitCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (enabled == null) enabled = Boolean.TRUE;
        if (hitCount == null) hitCount = 0;
        if (description == null) description = "";
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
