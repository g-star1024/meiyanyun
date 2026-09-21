package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "customer_merge", indexes = {
        @Index(name = "idx_customer_merge_master", columnList = "master_id"),
        @Index(name = "idx_customer_merge_merged", columnList = "merged_id"),
        @Index(name = "idx_customer_merge_status", columnList = "status")
})
@Getter @Setter @NoArgsConstructor
public class CustomerMerge {

    public static final String STATUS_PROPOSED = "PROPOSED";
    public static final String STATUS_REVIEWING = "REVIEWING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_MERGED = "MERGED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_NOT_DUPLICATE = "NOT_DUPLICATE";

    @Id
    @Column(name = "merge_id", length = 20)
    private String mergeId;                   // MG + 8位日期 + "-" + 6位序号（当日 max+1）

    @Column(name = "master_id", nullable = false, length = 16)
    private String masterId;                  // 胜方（Survivorship：创建时间最早，用户可手动换边）

    @Column(name = "merged_id", nullable = false, length = 16)
    private String mergedId;                  // 被合并方（单笔一个 merged，多单据多行）

    @Column(name = "group_type", nullable = false, length = 16)
    private String groupType;                 // POOL / SAME_STORE / CROSS_STORE（快照自候选对）

    @Column(name = "match_reasons", length = 64)
    private String matchReasons;              // 命中理由 CSV：PHONE / NAME_BIRTHDAY / DEVICE / IDCARD

    @Column(precision = 3, scale = 2)
    private BigDecimal score;                 // 快照 0.95

    @Column(length = 512)
    private String reason;                    // 人工填写的合并事由（前端确认弹窗传入）

    @Column(nullable = false, length = 16)
    private String status;                    // PROPOSED/REVIEWING/APPROVED/MERGED/REJECTED/NOT_DUPLICATE

    @Column(name = "idem_key", unique = true, length = 64)
    private String idemKey;                   // 幂等键：前端 clientToken 或 pairId:masterId 服务端兜底

    @Column(name = "requested_by", nullable = false, length = 16)
    private String requestedBy;               // 发起人工号

    @Column(name = "approved_by", length = 16)
    private String approvedBy;                // 审批人工号（直接执行时=requested_by）

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "executed_at")
    private OffsetDateTime executedAt;        // 迁移执行完成时间

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
