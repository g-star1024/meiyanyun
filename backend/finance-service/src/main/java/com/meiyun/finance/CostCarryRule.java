package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * cost_carry_rule 期末成本结转规则（B11，DESIGN §4.2，JPA ddl-auto）。
 *
 * <p>后台可配、随时启停调整：四类成本中 MATERIAL/LOSS 已随业务实时闭环，
 * 模板化结转仅覆盖 DEPRECIATION（折旧，ASSET 按资产直线法汇总）与
 * LABOR（人工：BASE_SALARY 在岗底薪合计 / COMMISSION 当月已审批提成合计 / FIXED 固定额）。
 * store_code 为空 = 全门店通用；run_on_close=true 时月结封账自动先结转。
 * 金额 Long「分」；结转结果落 fund_entry 成本镜像（TK-* OUT / COST / SYSTEM）+ cost_allocation，
 * 幂等键 CARRY:{ruleId}:{periodMonth}:{storeCode}。
 */
@Entity
@Table(name = "cost_carry_rule")
@Getter @Setter @NoArgsConstructor
public class CostCarryRule {

    @Id
    @Column(name = "rule_id", length = 24)
    private String ruleId;

    @Column(name = "rule_name", nullable = false, length = 64)
    private String ruleName;

    /** DEPRECIATION 折旧 / LABOR 人工。 */
    @Column(name = "cost_type", nullable = false, length = 16)
    private String costType;

    /** ASSET 资产折旧 / BASE_SALARY 底薪合计 / COMMISSION 已审批提成 / FIXED 固定额。 */
    @Column(name = "calc_mode", nullable = false, length = 16)
    private String calcMode;

    /** FIXED 模式每月固定额（分），其余模式为空。 */
    @Column(name = "fixed_amount")
    private Long fixedAmount;

    /** 适用门店（空 = 全门店通用）。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 封账时是否自动执行（默认 true；false 需手动点结转）。 */
    @Column(name = "run_on_close", nullable = false)
    private Boolean runOnClose = true;

    @Column(length = 256)
    private String remark;

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
