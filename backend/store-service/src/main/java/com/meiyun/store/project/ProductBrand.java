package com.meiyun.store.project;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 品牌档案（B14 项目目录三级主数据第一级，集团级，DESIGN-P5 §3.1）。
 * 品牌为上游厂商/供应商品牌（如艾尔建、华熙生物），brand_code 全局唯一；
 * status ACTIVE/INACTIVE 受控停用，不物理删。无 store_code，集团统一定义。
 */
@Entity
@Table(name = "product_brand",
        uniqueConstraints = @UniqueConstraint(name = "uk_brand_code", columnNames = "brand_code"))
@Getter
@Setter
@NoArgsConstructor
public class ProductBrand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 品牌编码（全局唯一，如 BR-ALLERGAN） */
    @Column(name = "brand_code", nullable = false, length = 32)
    private String brandCode;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "short_name", length = 32)
    private String shortName;

    /** 产地 */
    @Column(length = 64)
    private String origin;

    /** 供应商 */
    @Column(length = 128)
    private String supplier;

    /** ACTIVE 启用 / INACTIVE 停用 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 头像色（前端品牌卡片轮色；新建可空由前端兜底） */
    @Column(name = "logo_color", length = 16)
    private String logoColor;

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
    }
}
