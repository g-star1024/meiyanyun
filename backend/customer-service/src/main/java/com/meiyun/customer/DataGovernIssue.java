package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 数据治理-质量问题单（T2-B1 / DESIGN-T2，表 data_govern_issue / V64）。
 * rule_id 逻辑引用 data_govern_rule（零物理外键）；rule_name/table_name/column_name 冗余便于展示。
 * 列 error_count 对应前端契约字段 count（count 为 SQL 保留字避让，API 序列化映射回 count）。
 * 状态机三态：OPEN→[RESOLVED,IGNORED]；RESOLVED/IGNORED 终态（迁移前置校验在 T2GovernService，中文 4xx）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "data_govern_issue")
public class DataGovernIssue {

    /** 状态机三态。 */
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_IGNORED = "IGNORED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 来源规则逻辑引用（无物理外键）。 */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_name", nullable = false, length = 128)
    private String ruleName;

    @Column(name = "table_name", nullable = false, length = 64)
    private String tableName;

    @Column(name = "column_name", nullable = false, length = 64)
    private String columnName;

    @Column(name = "sample", nullable = false, columnDefinition = "text")
    private String sample;

    /** 异常条数（契约字段 count，保留字避让）。 */
    @Column(name = "error_count", nullable = false)
    private Integer errorCount;

    @Column(name = "status", nullable = false, length = 8)
    private String status;

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @PrePersist
    void prePersist() {
        if (columnName == null) columnName = "";
        if (sample == null) sample = "";
        if (errorCount == null) errorCount = 0;
        if (status == null) status = STATUS_OPEN;
        if (detectedAt == null) detectedAt = OffsetDateTime.now();
    }
}
