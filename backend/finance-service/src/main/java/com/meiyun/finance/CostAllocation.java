package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * cost_allocation 成本分摊（B5 成本域业务表，JPA ddl-auto）。
 *
 * <p>四类成本：MATERIAL 耗材领用（领用终审自动，对应 fund_entry TK-MATERIAL/OUT/ERP）、
 * LOSS 耗材报损（报损终审自动，TK-LOSS/OUT/ERP）、DEPRECIATION 折旧分摊 / LABOR 人工成本
 * （期末人工录入，POST /api/finance/cost-allocation，对应 TK-DEPRECIATION/TK-LABOR/OUT/MANUAL）。
 * 金额 Long「分」始终为正；sourceRef 为来源单据号（领用/报损=todoNo；人工=COST 单号），
 * 同 fund_entry 的 bizRef，fund_entry idem_key 唯一保证不重复落成本。
 */
@Entity
@Table(name = "cost_allocation")
@Getter @Setter @NoArgsConstructor
public class CostAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cost_id")
    private Long costId;

    /** 所属月份（每月 1 号，UTC）。 */
    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 成本类型：MATERIAL 耗材 / DEPRECIATION 折旧 / LOSS 报损 / LABOR 人工。 */
    @Column(name = "cost_type", nullable = false, length = 16)
    private String costType;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "source_ref", nullable = false, length = 48)
    private String sourceRef;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
