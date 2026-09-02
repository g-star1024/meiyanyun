package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 方案子项（consult_plan_item）：方案单的项目/卡项明细（名称/规格/数量/单价/小计/风险标签）。
 *
 * <p>金额单位「分」（unitPrice/amount），各子项 amount 之和 = consult_plan.planAmount。
 * 签病历生成缴费单时映射为 order_item。plan_id 为逻辑外键（与既有业务表一致，不建物理 JPA 关联）。
 */
@Entity
@Table(name = "consult_plan_item")
@Getter @Setter @NoArgsConstructor
public class PlanItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "plan_id", nullable = false, length = 24)
    private String planId;

    /** 行号（从 1 起） */
    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    /** 目录项编码（可空，零售/自由项为空） */
    @Column(name = "item_code", length = 32)
    private String itemCode;

    @Column(name = "item_name", nullable = false, length = 64)
    private String itemName;

    @Column(length = 64)
    private String spec;

    @Column(nullable = false)
    private Integer qty;

    /** 单价（分） */
    @Column(name = "unit_price", nullable = false)
    private Long unitPrice;

    /** 小计（分）= unitPrice × qty */
    @Column(nullable = false)
    private Long amount;

    /** 风险标签（逗号分隔，如 LASER/INJECTION/ANESTHESIA） */
    @Column(name = "risk_tags", length = 128)
    private String riskTags;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (qty == null) qty = 1;
        if (lineNo == null) lineNo = 1;
        if (amount == null && unitPrice != null && qty != null) amount = unitPrice * qty;
    }
}
