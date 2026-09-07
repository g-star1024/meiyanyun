package com.meiyun.store.project;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 品类档案（B14 项目目录第二级，集团级二级树，DESIGN-P5 §3.2）。
 * category_code 全局唯一；brand_id 归属品牌；parent_id 为空表示一级品类。
 * 删除规则：本品类或其子品类名下仍有 SKU 时拒绝（422），通过后连带删除子品类。
 */
@Entity
@Table(name = "product_category",
        uniqueConstraints = @UniqueConstraint(name = "uk_category_code", columnNames = "category_code"))
@Getter
@Setter
@NoArgsConstructor
public class ProductCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 品类编码（全局唯一，如 CT-INJECT） */
    @Column(name = "category_code", nullable = false, length = 32)
    private String categoryCode;

    @Column(nullable = false, length = 64)
    private String name;

    /** 所属品牌（逻辑外键 product_brand.id） */
    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    /** 父品类（空=一级品类） */
    @Column(name = "parent_id")
    private Long parentId;

    /** ACTIVE 启用 / INACTIVE 停用 */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Integer sort;

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
        if (sort == null) sort = 0;
    }
}
