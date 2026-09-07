package com.meiyun.store.catalog;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 卡项/疗程目录模板（B15，DESIGN-P4 §4.5）。
 * 门店售卖的卡项（CARD，CD- 前缀）与疗程（COURSE，CS- 前缀）模板定义：
 * 次数、有效期、售价/划线价、是否可转赠、上下架、包含项目（自由文本 JSON 数组）。
 * store_code 空串 = 集团通用模板（全门店可见可售）；同店同编码唯一。
 * 本批只做模板定义与上下架；售卡开卡（member_card 实例）留后续批次。
 */
@Entity
@Table(name = "catalog_product",
        uniqueConstraints = @UniqueConstraint(name = "uk_catalog_store_code",
                columnNames = {"store_code", "product_code"}))
@Getter
@Setter
@NoArgsConstructor
public class CatalogProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 门店码（SST01 等）；空串为集团通用模板 */
    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 商品编码：卡项 CD-xxx / 疗程 CS-xxx（后端生成，全局递增） */
    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    /** 商品名称 */
    @Column(nullable = false, length = 64)
    private String name;

    /** CARD 卡项 / COURSE 疗程 */
    @Column(name = "product_type", nullable = false, length = 16)
    private String productType;

    /** 分类（自由文本，如 储值卡 / 抗衰疗程） */
    @Column(length = 32)
    private String category;

    /** 总次数（卡项通常 1，疗程多次） */
    @Column(nullable = false)
    private Integer sessions;

    /** 有效期天数 */
    @Column(name = "validity_days", nullable = false)
    private Integer validityDays;

    /** 售价（分） */
    @Column(name = "price_fen", nullable = false)
    private Long priceFen;

    /** 划线价/原价（分） */
    @Column(name = "original_price_fen", nullable = false)
    private Long originalPriceFen;

    /** 是否允许转赠 */
    @Column(nullable = false)
    private Boolean transferable;

    /** ON_SHELF 上架中 / OFF_SHELF 已下架 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 包含项目（JSON 数组字符串，一期项目名自由文本，不强制 SKU 外键） */
    @Column(columnDefinition = "text")
    private String includes;

    /** 说明 */
    @Column(length = 1000)
    private String description;

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
        if (status == null) status = "ON_SHELF";
        if (sessions == null) sessions = 1;
        if (transferable == null) transferable = false;
        if (priceFen == null) priceFen = 0L;
        if (originalPriceFen == null) originalPriceFen = 0L;
        if (storeCode == null) storeCode = "";
    }
}
