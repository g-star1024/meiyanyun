package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * commission_rule 提成规则（B9 薪酬提成域业务表，JPA ddl-auto）。
 *
 * <p>规则后台可配、随时停用，变更不追溯历史期间（历史提成单在生成时已快照 rule_name/tiers）。
 * 阶梯超额累进：tiers_json 形如 {@code [{"min":0,"rate":600},{"min":8000000,"rate":800}]}，
 * min 为业绩基数下限（Long「分」），rate 为提成率（万分位：600=6%）；试算时跨档只对超出部分按高档。
 * base 为基数口径：WRITEOFF 划扣确认收入 / ORDER 收款 / RECHARGE 充值（RECHARGE 一期预留）；
 * role 为适用角色：CONSULTANT 咨询师 / DOCTOR 医生 / BEAUTICIAN 美容师。
 */
@Entity
@Table(name = "commission_rule")
@Getter @Setter @NoArgsConstructor
public class CommissionRule {

    @Id
    @Column(name = "rule_id", length = 24)
    private String ruleId;

    @Column(name = "rule_name", nullable = false, length = 64)
    private String ruleName;

    /** 基数口径：WRITEOFF / ORDER / RECHARGE。 */
    @Column(nullable = false, length = 16)
    private String base;

    /** 适用角色：CONSULTANT / DOCTOR / BEAUTICIAN。 */
    @Column(nullable = false, length = 16)
    private String role;

    /** 阶梯 JSON：[{"min":0,"rate":600},...]，min 单位分、rate 万分位。 */
    @Column(name = "tiers_json", nullable = false, columnDefinition = "text")
    private String tiersJson;

    /** 启用/停用（随时调整，停用后不影响已生成提成单）。 */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_by", length = 32)
    private String updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
