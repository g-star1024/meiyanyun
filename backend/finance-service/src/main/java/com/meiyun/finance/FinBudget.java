package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * fin_budget 年度预算（B5 预算管控业务表，JPA ddl-auto）。
 *
 * <p>预算仅做「额度控制 + 实际发生额对比 + 超支预警」：实际额由前端只读镜像
 * revenue_monthly / cost_allocation，预算超额只提示，不反向触达资金池。
 * 金额 Long「分」；唯一键（预算年度 + 科目码）。8 个预算科目：
 * REVENUE/COST/MATERIAL/LABOR/DEPRECIATION/LOSS/MARKETING/RENT，
 * 其中 MARKETING/RENT 为费用类（EXP），无实际发生数据源，仅作额度管控。
 */
@Entity
@Table(name = "fin_budget",
        uniqueConstraints = @UniqueConstraint(columnNames = {"budget_year", "subject_code"}))
@Getter @Setter @NoArgsConstructor
public class FinBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "budget_id")
    private Long budgetId;

    @Column(name = "budget_year", nullable = false)
    private Integer budgetYear;

    @Column(name = "subject_code", nullable = false, length = 16)
    private String subjectCode;

    @Column(name = "budget_fen", nullable = false)
    private Long budgetFen;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;
}
