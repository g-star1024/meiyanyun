package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 咨询方案单（M4-09 咨询 → M4 医生审核 → 签病历生成缴费单）。
 *
 * <p>状态机（对齐前端 consultation mock store，合规顺序：病历先于收费、收费先于治疗）：
 * <pre>
 *   PENDING 待咨询 → ACTIVE 咨询中 → PENDING_REVIEW 待医生审核
 *     → APPROVED 审核通过·待写病历 → READY_PAY 待支付（签病历自动生成缴费单）
 *     → PAID 已支付·待治疗（下期接 TREATING/DONE）
 *   打回：PENDING_REVIEW → REJECTED（咨询师改单重提）；可 ABANDONED 作废。
 * </pre>
 * 状态码存英文枚举（与前端 domain ConsultStatus 一致，前端 pill 映射中文展示）。
 * 金额单位「分」（planAmount/planCost）。
 */
@Entity
@Table(name = "consult_plan")
@Getter @Setter @NoArgsConstructor
public class ConsultPlan {

    @Id
    @Column(name = "plan_id", length = 24)
    private String planId;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "store_code", length = 16)
    private String storeCode;

    /** 咨询师（开方案） */
    @Column(name = "consultant_id", length = 32)
    private String consultantId;

    /** 审核医生 */
    @Column(name = "doctor_id", length = 32)
    private String doctorId;

    @Column(name = "arrival_id", length = 32)
    private String arrivalId;

    /** 状态码：PENDING/ACTIVE/PENDING_REVIEW/APPROVED/READY_PAY/PAID/REJECTED/ABANDONED/TREATING/DONE */
    @Column(nullable = false, length = 16)
    private String status;

    /** 咨询结论 / 诊断说明（将作为病历诊断基础） */
    @Column(columnDefinition = "TEXT")
    private String conclusion;

    /** 方案合计（分）= 各 plan_item 小计之和 */
    @Column(name = "plan_amount")
    private Long planAmount;

    /** 方案成本（分），毛利率/权限可见 */
    @Column(name = "plan_cost")
    private Long planCost;

    /** 禁忌/面诊情况（JSON：pregnant/allergy/scarConstitution/.../note） */
    @Column(name = "contraindications", columnDefinition = "TEXT")
    private String contraindicationsJson;

    @Column(name = "consent_consultant", nullable = false)
    private Boolean consentConsultant = false;

    @Column(name = "consent_customer", nullable = false)
    private Boolean consentCustomer = false;

    /** 客户手写电子签名（dataURL） */
    @Column(name = "consent_signature", columnDefinition = "TEXT")
    private String consentSignatureDataUrl;

    @Column(name = "consent_signer_name", length = 64)
    private String consentSignerName;

    @Column(name = "consent_doc_version", length = 32)
    private String consentDocVersion;

    @Column(name = "consent_at")
    private OffsetDateTime consentAt;

    @Column(name = "skin_report_id", length = 32)
    private String skinReportId;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "reviewed_by", length = 32)
    private String reviewedBy;

    @Column(name = "reviewed_by_name", length = 64)
    private String reviewedByName;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    /** 首程病历号（签名后生成） */
    @Column(name = "emr_id", length = 32)
    private String emrId;

    @Column(name = "emr_signed_at")
    private OffsetDateTime emrSignedAt;

    /** 签病历后自动生成的缴费单号（txn_order.order_no） */
    @Column(name = "order_no", length = 24)
    private String orderNo;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = "PENDING";
        if (consentConsultant == null) consentConsultant = false;
        if (consentCustomer == null) consentCustomer = false;
    }
}
