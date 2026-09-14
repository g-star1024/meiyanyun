package com.meiyun.store.procurement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 采购单明细行（B49 卡5）。单价 {@code unit_price_fen} 单位「分」；
 * {@code received_qty} 随收货批次累加，是 PARTIAL/RECEIVED 状态判定依据。
 * sku_code 收货时必须在收货门店 consumable 已建档，否则整批 422 中文拦截。
 */
@Entity
@Table(name = "purchase_order_item",
        indexes = {
                @Index(name = "idx_po_item_po", columnList = "po_id"),
                @Index(name = "idx_po_item_sku", columnList = "sku_code")
        })
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "po_id", nullable = false)
    private Long poId;

    /** 行序（同单内从 1 起，确定性收货批次号用） */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "sku_code", nullable = false, length = 32)
    private String skuCode;

    @Column(nullable = false, length = 64)
    private String name;

    /** 品牌（前端明细列；供应商物料口径） */
    @Column(length = 32)
    private String brand;

    @Column(nullable = false, length = 8)
    private String unit;

    @Column(name = "unit_price_fen", nullable = false)
    private long unitPriceFen;

    @Column(nullable = false)
    private int qty;

    @Column(name = "received_qty", nullable = false)
    private int receivedQty;
}
