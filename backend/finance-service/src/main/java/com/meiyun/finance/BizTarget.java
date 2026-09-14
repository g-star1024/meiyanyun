package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * biz_target 经营目标（B49 卡8 目标管理业务表，JPA ddl-auto）。
 *
 * <p>集团目标 → 区域/门店分解树（children_ids 逗号串存子目标业务 id，三级：
 * group → region → store）+ 进度追踪 + 审批状态机（DRAFT → PENDING → APPROVED/REJECTED）。
 * target_id 为可读业务 id（G1/G1-E/T01-R 风格），前端树按字符串 id 引用，与 mock 契约一致。
 * 目标值/实际值为管理指标（非资金流水），NUMERIC 存原数值 + unit（万元/人/%/人次），
 * 手工维护，与 mock 语义一致；REVENUE 实际值自动聚合 revenue_monthly 增强登记 backlog。
 */
@Entity
@Table(name = "biz_target",
        uniqueConstraints = @UniqueConstraint(columnNames = {"target_id"}))
@Getter @Setter @NoArgsConstructor
public class BizTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 可读业务 id（G1/G1-E/T01-R 风格，前端树引用键） */
    @Column(name = "target_id", nullable = false, length = 32)
    private String targetId;

    @Column(name = "owner_id", nullable = false, length = 32)
    private String ownerId;

    @Column(name = "owner_name", nullable = false, length = 64)
    private String ownerName;

    /** GROUP / REGION / STORE */
    @Column(name = "owner_type", nullable = false, length = 16)
    private String ownerType;

    /** REVENUE / NEW_CUSTOMER / REPURCHASE_RATE / PROCEDURE_COUNT / SATISFACTION */
    @Column(name = "metric", nullable = false, length = 32)
    private String metric;

    /** YEAR / QUARTER / MONTH */
    @Column(name = "period", nullable = false, length = 16)
    private String period;

    /** 如 2026年度 / 2026-Q3 / 2026-08 */
    @Column(name = "period_label", nullable = false, length = 32)
    private String periodLabel;

    @Column(name = "target_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal targetValue;

    @Column(name = "current_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal currentValue;

    /** 万元 / 人 / % / 人次 */
    @Column(name = "unit", nullable = false, length = 16)
    private String unit;

    @Column(name = "weight", nullable = false)
    private Integer weight;

    /** DRAFT / PENDING / APPROVED / REJECTED */
    @Column(name = "approval", nullable = false, length = 16)
    private String approval;

    /** 分解的子目标业务 id 逗号串（可空） */
    @Column(name = "children_ids", length = 256)
    private String childrenIds;

    /** 幂等键（新建去重，可空） */
    @Column(name = "idem_key", length = 64, unique = true)
    private String idemKey;

    @Column(name = "submitted_by", length = 64)
    private String submittedBy;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "reject_reason", length = 256)
    private String rejectReason;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
