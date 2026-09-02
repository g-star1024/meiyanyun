package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 订单收费子项（order_item）：医美一笔订单可含多个收费项目（项目 + 数量 + 单价 + 小计）。
 *
 * <p>金额单位「分」（bigint），与 {@link TxnOrder#getAmount()} 一致；一笔订单各子项 amount 之和 = 订单总额。
 * 订单主表 {@code txn_order.project} 冗余首个/主项目名，用于列表概要；明细以本表为准。
 * order_no 为逻辑外键（与既有业务表一致，不建物理 JPA 关联）。
 */
@Entity
@Table(name = "order_item")
@Getter @Setter @NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "order_no", nullable = false, length = 24)
    private String orderNo;

    /** 行号（从 1 起，同订单内递增）。 */
    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "item_name", nullable = false, length = 64)
    private String itemName;

    @Column(nullable = false)
    private Integer qty;

    /** 单价（分）。 */
    @Column(name = "unit_price", nullable = false)
    private Long unitPrice;

    /** 小计（分）= unitPrice × qty。 */
    @Column(nullable = false)
    private Long amount;

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
