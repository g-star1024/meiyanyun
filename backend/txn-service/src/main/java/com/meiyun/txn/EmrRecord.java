package com.meiyun.txn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 电子病历（emr_record，EMR 独立域，P5-B27）。
 *
 * <p>状态机：DRAFT 草稿 → SIGNED 已签名 → ARCHIVED 已归档；已签名/归档单可「修订」生成
 * version+1 的新草稿（parent_id 回溯源单）。病历号 EM + yyyyMMdd + - + 6 位查库序号；
 * 修订单 PK 直接存「源号-R{version}」（不进查库池，max 序号按 char_length 排除修订号）。
 *
 * <p>病历可由医生/顾问直接新建（需建档客户，无 customerId 拒收），也可由方案单签首程病历/
 * 完成治疗联动落库（consult_id 回挂方案单号，consult_id+type 幂等防双写）。
 */
@Entity
@Table(name = "emr_record", indexes = {
        @Index(name = "idx_emr_store_status", columnList = "store_code,status"),
        @Index(name = "idx_emr_customer", columnList = "customer_id"),
        @Index(name = "idx_emr_consult", columnList = "consult_id")
})
@Getter @Setter @NoArgsConstructor
public class EmrRecord {

    @Id
    @Column(name = "emr_no", length = 24)
    private String emrNo;

    @Column(name = "customer_id", length = 16)
    private String customerId;

    /** 客户姓名（自由建档名也落库；customerId 为空时的展示兜底）。 */
    @Column(name = "customer_name", nullable = false, length = 64)
    private String customerName;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 病历类型：FIRST_VISIT 初诊/FOLLOW_UP 复诊/TREATMENT 治疗/PROCEDURE 操作。 */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    /** 状态：DRAFT/SIGNED/ARCHIVED。 */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "chief_complaint", columnDefinition = "TEXT")
    private String chiefComplaint;

    @Column(name = "present_illness", columnDefinition = "TEXT")
    private String presentIllness;

    @Column(name = "past_history", columnDefinition = "TEXT")
    private String pastHistory;

    @Column(columnDefinition = "TEXT")
    private String allergy;

    @Column(columnDefinition = "TEXT")
    private String diagnosis;

    @Column(columnDefinition = "TEXT")
    private String treatment;

    @Column(columnDefinition = "TEXT")
    private String prescription;

    /** 责任医生（顾问建草稿可空，签名时盖当前 JWT 人）。 */
    @Column(name = "doctor_id", length = 32)
    private String doctorId;

    @Column(name = "doctor_name", length = 64)
    private String doctorName;

    @Column(name = "related_appointment_no", length = 24)
    private String relatedAppointmentNo;

    @Column(name = "related_order_no", length = 24)
    private String relatedOrderNo;

    /** 关联方案单号（consult_plan.plan_id）。 */
    @Column(name = "consult_id", length = 24)
    private String consultId;

    @Column(nullable = false)
    private Integer version = 1;

    /** 修订源病历号（首版为空）。 */
    @Column(name = "parent_id", length = 24)
    private String parentId;

    @Column(name = "signed_by", length = 32)
    private String signedBy;

    @Column(name = "signed_by_name", length = 64)
    private String signedByName;

    @Column(name = "signed_at")
    private OffsetDateTime signedAt;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "DRAFT";
        if (version == null) version = 1;
    }
}
