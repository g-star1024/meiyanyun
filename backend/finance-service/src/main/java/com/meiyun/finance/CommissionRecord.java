package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * commission_record 提成单（B9，JPA ddl-auto）。
 *
 * <p>月度 per 人：UNIQUE(period, staff_id)，生成幂等——同月同人已存在则重算覆盖
 * （PAID 单保持状态不改，其余回 DRAFT）。金额全部 Long「分」。
 * 规则快照：rule_id/rule_name/tiers_json 在生成时固化，规则后改不影响本单（调整不追溯）。
 *
 * <p>状态机：DRAFT 待提交 → SUBMITTED 待审批 → APPROVED 已审批待发放 → PAID 已发放
 * （SUBMITTED 可 → REJECTED 已驳回）。<b>资金红线</b>：系统只试算+审批+登记，
 * 发放走外部薪酬系统，markPaid 仅镜像回传状态，绝不在 fund_entry 生成实付分录。
 */
@Entity
@Table(name = "commission_record",
       uniqueConstraints = @UniqueConstraint(name = "uk_commission_period_staff",
                                             columnNames = {"period", "staff_id"}))
@Getter @Setter @NoArgsConstructor
public class CommissionRecord {

    @Id
    @Column(name = "record_id", length = 32)
    private String recordId;

    /** 归属月份（yyyy-MM-01）。 */
    @Column(nullable = false)
    private LocalDate period;

    @Column(name = "staff_id", nullable = false, length = 16)
    private String staffId;

    @Column(name = "staff_name", nullable = false, length = 32)
    private String staffName;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "rule_id", length = 24)
    private String ruleId;

    /** 规则名快照（规则后改不影响本单）。 */
    @Column(name = "rule_name", length = 64)
    private String ruleName;

    /** 当月业绩基数（分）。 */
    @Column(name = "base_amount", nullable = false)
    private Long baseAmount;

    /** 业绩单数。 */
    @Column(name = "order_count", nullable = false)
    private Integer orderCount;

    /** 试算明细快照 JSON：[{"label":..,"amount":..,"rate":..,"commission":..}]。 */
    @Column(name = "tiers_json", columnDefinition = "text")
    private String tiersJson;

    /** 提成额（分）。 */
    @Column(nullable = false)
    private Long commission;

    /** DRAFT / SUBMITTED / APPROVED / PAID / REJECTED。 */
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(length = 256)
    private String remark;

    private String approver;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
