package com.meiyun.store.bom;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 项目用料配方（B10，DDL 见 DESIGN-P3 §6.2）。
 *
 * <p>一行 = 某治疗项目在某门店做一次所消耗的一个 SKU 标准用量。
 * store_code 为空串表示<b>集团模板</b>（全部门店回落）；门店行覆盖同名 SKU 的集团行
 * （匹配优先级：门店行 > 集团模板）。project_name 与 writeoff_record.project /
 * txn_order.project 一致，为划扣自动扣料的勾兑键。用量为整数（SKU 最小单位，一期不支持小数）。
 */
@Entity
@Table(name = "project_bom",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_bom",
                columnNames = {"project_name", "store_code", "sku_code"}))
@Getter
@Setter
@NoArgsConstructor
public class ProjectBom {

    /** 配方行号：BOM + yyyyMMdd + - + 6 位（BomNoGenerator 生成） */
    @Id
    @Column(name = "bom_id", length = 24)
    private String bomId;

    /** 项目名（勾兑键，如「水光针单次」） */
    @Column(name = "project_name", nullable = false, length = 64)
    private String projectName;

    /** 门店码；空串 = 集团模板（门店行优先回落） */
    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 耗材 SKU 编码（consumable.sku_code 逻辑外键） */
    @Column(name = "sku_code", nullable = false, length = 32)
    private String skuCode;

    /** 标准用量（整数，单位取 SKU 主单位） */
    @Column(nullable = false)
    private int qty;

    /** 启用开关（停用行不参与自动扣料） */
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private java.time.OffsetDateTime createdAt;

    @Column(name = "updated_by", length = 32)
    private String updatedBy;

    @Column(name = "updated_at")
    private java.time.OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = java.time.OffsetDateTime.now();
        if (updatedAt == null) updatedAt = createdAt;
    }
}
