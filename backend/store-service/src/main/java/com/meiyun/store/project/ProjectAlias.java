package com.meiyun.store.project;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 项目别名映射（P5-B96 卡1，DESIGN-T2 §3 D2）：订单侧泛化项目名 → product_sku 的映射层。
 * 订单/营销域落库的项目名为泛化名（如「光子嫩肤」），与 SKU 精确名（如「M22王者之冠 光子嫩肤」）
 * 不一致，品类归桶需经本表二级命中：skuCategoryMap 精确名未命中时查别名。
 *
 * <p>store_code 可空：NULL=全局别名（集团级，运营统一维护），非空=门店级覆盖（同 alias 门店级
 * 优先于全局，两级命中）。sku 为指向 product_sku.sku 的逻辑外键（不建物理外键，与 brand_id/
 * category_id 同惯例）。status ACTIVE/INACTIVE 受控停用。
 *
 * <p>唯一性：uk_alias_scope(alias, store_code) 仅兜底门店级——PG 复合唯一约束中 NULL 行互不冲突，
 * 全局别名（store_code IS NULL）的查重由 ProjectService 应用层保证（实体层注释如实标注）。
 */
@Entity
@Table(name = "project_alias",
        uniqueConstraints = @UniqueConstraint(name = "uk_alias_scope", columnNames = {"alias", "store_code"}))
@Getter
@Setter
@NoArgsConstructor
public class ProjectAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单侧泛化项目名（如「光子嫩肤」），同 scope 内唯一 */
    @Column(nullable = false, length = 64)
    private String alias;

    /** 门店码；NULL=全局别名（集团级），非空=门店级覆盖（两级命中时优先） */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    /** 指向 product_sku.sku 的逻辑外键 */
    @Column(nullable = false, length = 40)
    private String sku;

    /** ACTIVE 启用 / INACTIVE 停用（停用后不再参与两级命中） */
    @Column(nullable = false, length = 16)
    private String status;

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
