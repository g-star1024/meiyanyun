package com.meiyun.txn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 随访记录（followup，P5-B30 术后随访 SOP 引擎）。
 *
 * <p>字段 1:1 对齐前端 stores/followup.ts 的 Followup 活规格：
 * 状态机 PENDING（待回访）→ DONE（已回访）/ SKIPPED（无需回访）；plan_date 早于今天且仍 PENDING 视为超期。
 * 手动/核销生成的普通随访 sop_batch_id 为空；术后 SOP 自动排程的多节点共享一个批次号，
 * 并带 sop_stage / sop_label。超期未完成由 {@link FollowupSopDueJob} 巡检置 escalated=true 并升级提醒店长。</p>
 *
 * <p>service_date / plan_date 为按天粒度（业务时区 +8），用 LocalDate 落库便于按天比较；
 * 时间戳字段（created_at/done_at）用 OffsetDateTime。</p>
 */
@Entity
@Table(name = "followup", indexes = {
        @Index(name = "idx_followup_status_plan", columnList = "status,plan_date"),
        @Index(name = "idx_followup_batch", columnList = "sop_batch_id"),
        @Index(name = "idx_followup_customer", columnList = "customer_id"),
        @Index(name = "idx_followup_store", columnList = "store_code,status")
})
@Getter @Setter @NoArgsConstructor
public class Followup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 随访单号：HF + yyyyMMdd + - + 6 位序号（查库池 followup）。 */
    @Column(name = "followup_no", nullable = false, length = 24)
    private String followupNo;

    @Column(name = "customer_id", nullable = false, length = 32)
    private String customerId;

    @Column(name = "customer_name", nullable = false, length = 64)
    private String customerName;

    /** 随访项目（方案单项目名拼接，按 128 截断）。 */
    @Column(nullable = false, length = 128)
    private String project;

    @Column(name = "related_order_no", length = 32)
    private String relatedOrderNo;

    /** 门店码（排程时自方案单冗余，便于门店数据域 storeSpec 统一过滤）。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    /** 服务/治疗日期（SOP 第 0 天基准）。 */
    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    /** 计划回访日期（按天粒度）。 */
    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    /** 回访方式：PHONE/WECHAT/IN_STORE。 */
    @Column(nullable = false, length = 16)
    private String method;

    /** 状态：PENDING/DONE/SKIPPED。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 术后 SOP 节点阶段：CARE_24H/FOLLOWUP_3D/RECOVERY_7D/REVISIT_30D/MANUAL；普通随访为空。 */
    @Column(name = "sop_stage", length = 16)
    private String sopStage;

    /** SOP 节点名称（冗余存储，编排模板改名不影响已生成节点）。 */
    @Column(name = "sop_label", length = 64)
    private String sopLabel;

    /** 所属术后 SOP 批次号（同一次治疗的多节点共享；有值即 SOP 节点）。 */
    @Column(name = "sop_batch_id", length = 24)
    private String sopBatchId;

    /** SOP 节点是否已超时升级（超期巡检置 true 并提醒店长，只升级一次）。 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean escalated = false;

    /** 满意度 1-5，回访后填写。 */
    private Integer satisfaction;

    /** 恢复情况：GOOD/NORMAL/POOR。 */
    @Column(length = 16)
    private String recovery;

    @Column(name = "adverse_reaction", nullable = false, columnDefinition = "boolean default false")
    private boolean adverseReaction = false;

    @Column(name = "adverse_note", length = 500)
    private String adverseNote;

    @Column(name = "need_revisit", nullable = false, columnDefinition = "boolean default false")
    private boolean needRevisit = false;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "followup_by", length = 64)
    private String followupByName;

    @Column(name = "done_at")
    private OffsetDateTime doneAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = "PENDING";
        if (method == null) method = "PHONE";
    }
}
