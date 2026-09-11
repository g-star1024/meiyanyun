package com.meiyun.txn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 术后 SOP 排程批次（followup_sop_batch，P5-B30）。
 *
 * <p>同一次治疗「完成治疗」时一次性生成一条批次 + 多条 {@link Followup} 节点，
 * 多节点共享 batch_no（写入 followup.sop_batch_id）。source_plan_id 唯一约束做方案单维度幂等：
 * 一张方案单只允许排一次术后 SOP（treatDone AFTER_COMMIT 回调查库防重，重复触发直接跳过）。</p>
 */
@Entity
@Table(name = "followup_sop_batch",
        indexes = {
                @Index(name = "idx_sop_batch_customer", columnList = "customer_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sop_batch_source_plan", columnNames = "source_plan_id")
        })
@Getter @Setter @NoArgsConstructor
public class FollowupSopBatch {

    /** 批次号：SOP + yyyyMMdd + - + 6 位序号（查库池 followup_sop_batch）。 */
    @Id
    @Column(name = "batch_no", length = 24)
    private String batchNo;

    /** 来源方案单（consult_plan.plan_id），唯一：一张方案单只排一次 SOP。 */
    @Column(name = "source_plan_id", nullable = false, length = 24)
    private String sourcePlanId;

    @Column(name = "customer_id", nullable = false, length = 32)
    private String customerId;

    @Column(name = "customer_name", nullable = false, length = 64)
    private String customerName;

    @Column(nullable = false, length = 128)
    private String project;

    @Column(name = "related_order_no", length = 32)
    private String relatedOrderNo;

    @Column(name = "store_code", length = 16)
    private String storeCode;

    /** 治疗日期（SOP 第 0 天基准）。 */
    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    /** 本批生成的节点数。 */
    @Column(name = "node_count", nullable = false)
    private int nodeCount;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
