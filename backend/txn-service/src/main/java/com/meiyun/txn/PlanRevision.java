package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 方案单留痕（consult_plan_revision）：审核/改单/病历/收款等动作 append-only 记录。
 * kind: SUBMIT/RESUBMIT/APPROVE/REJECT/DOCTOR_EDIT/EMR_SIGN/PAY/ABANDON/TREAT_START/TREAT_DONE。
 * 与 audit_log 互补：本表承载方案单内联时间线（含字段变更 changes），audit_log 为全站合规留痕。
 */
@Entity
@Table(name = "consult_plan_revision")
@Getter @Setter @NoArgsConstructor
public class PlanRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rev_id")
    private Long revId;

    @Column(name = "plan_id", nullable = false, length = 24)
    private String planId;

    /** SUBMIT/RESUBMIT/APPROVE/REJECT/DOCTOR_EDIT/EMR_SIGN/PAY/ABANDON/... */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "actor_id", length = 32)
    private String actorId;

    @Column(name = "actor_name", length = 64)
    private String actorName;

    @Column(columnDefinition = "TEXT")
    private String reason;

    /** 字段变更（JSON：[{label,from,to}]，可空） */
    @Column(name = "changes_json", columnDefinition = "TEXT")
    private String changesJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
