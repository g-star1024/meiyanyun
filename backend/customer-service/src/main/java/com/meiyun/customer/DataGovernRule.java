package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 数据治理-质量规则（T2-B1 / DESIGN-T2，表 data_govern_rule / V63）。
 * 治理对象为库表字段（集团级数据资产），非单店行，故无 store_code 维度。
 * 状态字段 enabled 直改直存（无状态机）；last_check_at 可空=未检测（如 disabled 规则）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "data_govern_rule")
public class DataGovernRule {

    /** 规则类型五值。 */
    public static final String TYPE_NOT_NULL = "NOT_NULL";
    public static final String TYPE_UNIQUE = "UNIQUE";
    public static final String TYPE_RANGE = "RANGE";
    public static final String TYPE_REGEX = "REGEX";
    public static final String TYPE_CUSTOM = "CUSTOM";

    /** 严重度三值。 */
    public static final String SEVERITY_HIGH = "HIGH";
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    public static final String SEVERITY_LOW = "LOW";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "table_name", nullable = false, length = 64)
    private String tableName;

    @Column(name = "column_name", nullable = false, length = 64)
    private String columnName;

    @Column(name = "rule_type", nullable = false, length = 16)
    private String ruleType;

    @Column(name = "severity", nullable = false, length = 8)
    private String severity;

    @Column(name = "expression", nullable = false, columnDefinition = "text")
    private String expression;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "last_check_at")
    private OffsetDateTime lastCheckAt;

    @Column(name = "pass_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal passRate;

    @Column(name = "error_count", nullable = false)
    private Integer errorCount;

    @Column(name = "owner", nullable = false, length = 32)
    private String owner;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (columnName == null) columnName = "";
        if (expression == null) expression = "";
        if (enabled == null) enabled = Boolean.TRUE;
        if (passRate == null) passRate = new BigDecimal("100");
        if (errorCount == null) errorCount = 0;
        if (owner == null) owner = "";
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
