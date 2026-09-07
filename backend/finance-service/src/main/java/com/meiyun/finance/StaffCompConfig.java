package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * staff_comp_config 员工薪酬配置（B9，JPA ddl-auto）。
 *
 * <p>一人一当前生效单（status=ACTIVE），应用层保证同一 staff_id 仅一条 ACTIVE：
 * 调薪 = 旧单置 INACTIVE + 新单 ACTIVE，{@code effective_month} 起生效，<b>不追溯历史期间</b>。
 * 薪酬属财务敏感域，独立挂 finance 服务，org staff 档案不扩薪酬字段（staff_id 逻辑外键，不建物理 FK）。
 * base_salary 为月底薪（Long「分」）；commission_rule_id 可空（空=该员工无提成，仅底薪）。
 */
@Entity
@Table(name = "staff_comp_config")
@Getter @Setter @NoArgsConstructor
public class StaffCompConfig {

    @Id
    @Column(name = "comp_id", length = 24)
    private String compId;

    @Column(name = "staff_id", nullable = false, length = 16)
    private String staffId;

    @Column(name = "staff_name", nullable = false, length = 32)
    private String staffName;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 月底薪（分）。 */
    @Column(name = "base_salary", nullable = false)
    private Long baseSalary;

    /** 适用提成规则（可空=无提成）。 */
    @Column(name = "commission_rule_id", length = 24)
    private String commissionRuleId;

    /** 生效月（yyyy-MM-01）。 */
    @Column(name = "effective_month", nullable = false)
    private LocalDate effectiveMonth;

    /** ACTIVE 生效 / INACTIVE 停用（调薪旧单）。 */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

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
