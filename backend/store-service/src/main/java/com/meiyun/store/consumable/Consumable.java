package com.meiyun.store.consumable;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 耗材/物料档案（B5 成本库存域，DDL §2.5）。
 * 一店一 SKU 行：store_code + sku_code 唯一；cost_price 为移动平均成本价（单位：分）。
 * 库存余量不落本列，由 consumable_stock 表持有（扣减行锁，避免并发超扣）。
 */
@Entity
@Table(name = "consumable",
        uniqueConstraints = @UniqueConstraint(name = "uk_consumable_store_sku",
                columnNames = {"store_code", "sku_code"}))
@Getter
@Setter
@NoArgsConstructor
public class Consumable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 门店码（ST-SH-001 等）；数据域过滤依据 */
    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** SKU 编码（门店内唯一，如 HC-001） */
    @Column(name = "sku_code", nullable = false, length = 32)
    private String skuCode;

    @Column(nullable = false, length = 64)
    private String name;

    /** 分类：CONSUMABLE 耗材 / PRODUCT 产品 / DRUG 药品 / DEVICE 设备（对齐前端 InvCategory） */
    @Column(nullable = false, length = 16)
    private String category;

    @Column(length = 32)
    private String spec;

    @Column(nullable = false, length = 8)
    private String unit;

    /** 移动平均成本价（分）；入库按 (库存*旧均价+入库量*入库价)/新库存 重算 */
    @Column(name = "cost_price", nullable = false)
    private long costPrice;

    /** 安全库存（低于即预警；0 不预警） */
    @Column(name = "safety_stock", nullable = false)
    private int safetyStock;

    @Column(length = 64)
    private String supplier;

    @Column(length = 32)
    private String location;

    @Column(name = "created_at", nullable = false)
    private java.time.OffsetDateTime createdAt;

    @Column(name = "last_in_at")
    private java.time.OffsetDateTime lastInAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = java.time.OffsetDateTime.now();
    }
}
