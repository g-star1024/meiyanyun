package com.meiyun.store.consumable;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 耗材库存余量（B5）。与档案一对一，独立行以便扣减时行锁（SELECT ... FOR UPDATE）防超扣。
 */
@Entity
@Table(name = "consumable_stock",
        uniqueConstraints = @UniqueConstraint(name = "uk_consumable_stock_consumable",
                columnNames = {"consumable_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ConsumableStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumable_id", nullable = false)
    private Long consumableId;

    @Column(nullable = false)
    private int qty;

    @Column(name = "updated_at", nullable = false)
    private java.time.OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = java.time.OffsetDateTime.now();
    }
}
