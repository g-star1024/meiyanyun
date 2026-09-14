package com.meiyun.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 合规检查项（B49 卡9，DELIVERY-P5-B49 §卡9）：M1 集团屏 /m1-compliance。
 * <p>六类（QUALIFICATION 资质证照/CONSENT 知情同意/DRUG_TRACE 药品溯源/
 * PRIVACY 隐私合规/AD 医疗广告/INFECTION 院感管理）× 四态（PASS 合规/WARN 预警/
 * FAIL 不合规/PENDING 待检），按门店维度产生检查记录；FAIL 项必须整改并复检。</p>
 */
@Entity
@Table(name = "compliance_check")
@Getter
@Setter
@NoArgsConstructor
public class ComplianceCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 六类：QUALIFICATION/CONSENT/DRUG_TRACE/PRIVACY/AD/INFECTION（V31 CHECK 约束）。 */
    @Column(name = "category", nullable = false, length = 16)
    private String category;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    @Column(name = "requirement", nullable = false, length = 256)
    private String requirement;

    /** 门店名（mock 纯文本，含「全集团」集团级检查），不建外键。 */
    @Column(name = "store_name", nullable = false, length = 64)
    private String storeName;

    /** 四态：PASS/WARN/FAIL/PENDING（V31 CHECK 约束）。 */
    @Column(name = "status", nullable = false, length = 8)
    private String status;

    @Column(name = "last_check_at", nullable = false)
    private OffsetDateTime lastCheckAt;

    /** 最近检查人（中文姓名，SecurityContext.currentStaffName()）。 */
    @Column(name = "checker", nullable = false, length = 32)
    private String checker;

    @Column(name = "evidence", length = 256)
    private String evidence;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "remark", length = 512)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
