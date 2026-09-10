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

import java.time.OffsetDateTime;

/**
 * EMR 病历模板（emr_template，P5-B30 模板库）。
 *
 * <p>七段文本与 {@link EmrRecord} 同构（主诉/现病史/既往史/过敏史/诊断/治疗方案/医嘱），
 * 新建病历时一键套用，仅作录入提效，不改变病历状态机与合规留痕。</p>
 *
 * <p>模板可见范围：store_code 为 NULL 表示集团通用模板（全部门店可见）；
 * 有值为门店自建模板（仅本店可见）。停用模板 enabled=false 后不再出现在套用候选，
 * 但已套用产生的病历不受影响（模板不留外键，仅录入时复制文本）。</p>
 */
@Entity
@Table(name = "emr_template", indexes = {
        @Index(name = "idx_emr_tpl_store", columnList = "store_code,enabled")
})
@Getter @Setter @NoArgsConstructor
public class EmrTemplate {

    /** 模板号：EMT + yyyyMMdd + - + 6 位序号（查库池 emr_template）。 */
    @Id
    @Column(name = "template_no", length = 24)
    private String templateNo;

    @Column(nullable = false, length = 64)
    private String name;

    /** 适用病历类型：FIRST_VISIT/FOLLOW_UP/TREATMENT/PROCEDURE；NULL=全部类型通用。 */
    @Column(length = 16)
    private String type;

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

    /** 门店码：NULL=集团通用模板（全部门店可见）；有值=门店自建（仅本店可见）。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(nullable = false)
    private Boolean enabled = true;

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
        if (enabled == null) enabled = true;
    }
}
