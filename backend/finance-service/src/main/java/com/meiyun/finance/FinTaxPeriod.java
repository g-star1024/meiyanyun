package com.meiyun.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 增值税申报期登记簿（B63 卡4 L86，V39 fin_tax_period）。
 *
 * <p>对税局的增值税申报期，与 V7 settlement_period（内部对账封账期）语义不同、不复用：
 * 税务申报不封业务账。期间行懒创建（POST /tax-periods/ensure 幂等 upsert），零 Flyway 种子。
 *
 * <p>状态机：OPEN 未申报 → FILED 已按期申报 / LATE_FILED 逾期补申报 → AMENDED 更正申报；
 * CLOSED 为归档终态（本卡保留枚举，不做关闭动作）。申报时对五金额做快照，
 * 后续发票变动不改历史申报；同类型同期间仅一条非 CLOSED 记录（部分唯一索引）。
 *
 * <p>五金额均 Long「分」：output 销项 / input 可抵扣进项 / transferOut 进项转出 /
 * payable 应纳 / retained 期末留抵（进项＞销项差额结转下期，只诚实展示不办退税）。
 */
@Entity
@Table(name = "fin_tax_period")
@Getter
@Setter
@NoArgsConstructor
public class FinTaxPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** MONTH 按月 / QUARTER 按季。 */
    @Column(name = "period_type", nullable = false, length = 8)
    private String periodType;

    /** 期间标识：月 2026-08 / 季 2026-Q3。 */
    @Column(nullable = false, length = 16)
    private String period;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** 申报截止日（2026 年按税总办征科函〔2025〕64 号顺延日历）。 */
    @Column(nullable = false)
    private LocalDate deadline;

    /** OPEN / FILED / LATE_FILED / AMENDED / CLOSED。 */
    @Column(nullable = false, length = 16)
    private String status = "OPEN";

    @Column(name = "output_amount", nullable = false)
    private Long outputAmount = 0L;

    @Column(name = "input_amount", nullable = false)
    private Long inputAmount = 0L;

    @Column(name = "transfer_out_amount", nullable = false)
    private Long transferOutAmount = 0L;

    @Column(name = "payable_amount", nullable = false)
    private Long payableAmount = 0L;

    @Column(name = "retained_amount", nullable = false)
    private Long retainedAmount = 0L;

    @Column(name = "filed_at")
    private OffsetDateTime filedAt;

    @Column(length = 64)
    private String filer;

    @Column(length = 256)
    private String remark;

    @Column(name = "created_by", length = 16, nullable = false)
    private String createdBy = "system";

    @Column(name = "updated_by", length = 16, nullable = false)
    private String updatedBy = "system";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (updatedAt == null) updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
