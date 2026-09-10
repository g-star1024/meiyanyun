package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 分诊单（与到店登记一对一）：分诊动作 upsert 当前行，改派不新增行（无历史时间线表，见交付 Backlog）。
 *
 * <p>type：CONSULT（咨询）/ MEDICAL（医疗，被分派人须 DOCTOR 资质）/ SERVICE（服务）。
 * 分诊同事务创建 consult_plan 空 PENDING 草稿，plan_id 回挂本行。
 */
@Entity
@Table(name = "triage")
@Getter @Setter @NoArgsConstructor
public class Triage {

    @Id
    @Column(name = "tr_no", length = 24)
    private String trNo;

    /** 关联到店登记号（ah_no）。 */
    @Column(name = "arrival_id", nullable = false, length = 24)
    private String arrivalId;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 分诊类型：CONSULT / MEDICAL / SERVICE。 */
    @Column(nullable = false, length = 16)
    private String type;

    /** 首次分诊被分派人工号。 */
    @Column(name = "assigned_to", nullable = false, length = 32)
    private String assignedTo;

    /** 改派后当前被分派人（为空表示未改派，负责人取 assigned_to）。 */
    @Column(name = "forwarded_to", length = 32)
    private String forwardedTo;

    @Column(length = 256)
    private String note;

    /** 分诊同事务创建的 consult_plan 空草稿号（回挂）。 */
    @Column(name = "plan_id", length = 24)
    private String planId;

    @Column(name = "edited_by", length = 64)
    private String editedBy;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
