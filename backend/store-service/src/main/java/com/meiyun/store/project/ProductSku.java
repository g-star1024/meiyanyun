package com.meiyun.store.project;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 项目/产品 SKU（B14 项目目录第三级，集团级，DESIGN-P5 §3.3）。
 * sku 全局唯一；brand_id/category_id 逻辑外键；金额 Long「分」落库。
 * service_category 为门店经营服务大类（INJECTION/LASER/SKINCARE/BODY/EXAM，E2），
 * 与品牌品类树并存：品类管「品牌→产品线」归属，服务大类管价目页分类筛选。
 * store_types/risk_tags 为逗号串；status ACTIVE/INACTIVE 受控停用。
 */
@Entity
@Table(name = "product_sku",
        uniqueConstraints = @UniqueConstraint(name = "uk_sku", columnNames = "sku"))
@Getter
@Setter
@NoArgsConstructor
public class ProductSku {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SKU 编码（全局唯一，如 AGN-BTX-100） */
    @Column(nullable = false, length = 40)
    private String sku;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** 单位：次/支/盒/部位/疗程/小时 */
    @Column(nullable = false, length = 8)
    private String unit;

    /** 集团挂牌/指导价（分） */
    @Column(name = "list_price_fen", nullable = false)
    private Long listPriceFen;

    /** 成本价（分） */
    @Column(name = "cost_price_fen", nullable = false)
    private Long costPriceFen;

    /** ACTIVE 启用 / INACTIVE 停用 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 适用门店类型逗号串：FLAGSHIP,COMMUNITY,CLINIC */
    @Column(name = "store_types", nullable = false, length = 32)
    private String storeTypes;

    /** 预计时长（分钟） */
    @Column(name = "duration_min", nullable = false)
    private Integer durationMin;

    /** 服务大类（E2）：INJECTION/LASER/SKINCARE/BODY/EXAM，可空 */
    @Column(name = "service_category", length = 16)
    private String serviceCategory;

    /** 风险标签逗号串：INJECTION/LASER/HIGH_ENERGY/ANESTHESIA/PREGNANCY_RISK，可空 */
    @Column(name = "risk_tags", length = 64)
    private String riskTags;

    @Column(length = 255)
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
        if (status == null) status = "ACTIVE";
        if (listPriceFen == null) listPriceFen = 0L;
        if (costPriceFen == null) costPriceFen = 0L;
        if (durationMin == null) durationMin = 0;
        if (storeTypes == null) storeTypes = "";
    }
}
