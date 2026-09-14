package com.meiyun.store.procurement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 供应商档案（B49 卡5 采购供应链域）。集团级全局共享，不挂门店码——
 * 同一供应商可向集团内任意门店供货，采购单按 supplier_id 引用。
 */
@Entity
@Table(name = "supplier",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_code", columnNames = {"code"}))
@Getter
@Setter
@NoArgsConstructor
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 供应商编码（全局唯一，如 SUP-001） */
    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 32)
    private String contact;

    @Column(length = 32)
    private String phone;

    /** 账期（天） */
    @Column(name = "payment_terms", nullable = false)
    private int paymentTerms;

    /** 资质是否有效 */
    @Column(nullable = false)
    private boolean qualified;

    /** ACTIVE 合作中 / INACTIVE 停用（资质到期等） */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 255)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private java.time.OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = java.time.OffsetDateTime.now();
    }
}
