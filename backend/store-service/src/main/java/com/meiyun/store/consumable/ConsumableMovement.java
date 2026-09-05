package com.meiyun.store.consumable;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 耗材出入库流水（B5）。qty_change 正入负出；move_type：
 * PURCHASE 入库 / USE 领用出库 / SCRAP 报损出库 / ADJUST 盘点调整。
 * biz_ref 关联业务单号（双签领用/报损为审批待办号 AP...，入库为入库批次号）。
 * 金额一律按当时移动平均成本价（unit_cost 分）定格，便于成本侧镜像聚合。
 */
@Entity
@Table(name = "consumable_movement",
        indexes = {
                @Index(name = "idx_movement_consumable", columnList = "consumable_id"),
                @Index(name = "idx_movement_store", columnList = "store_code"),
                @Index(name = "idx_movement_biz_ref", columnList = "biz_ref")
        })
@Getter
@Setter
@NoArgsConstructor
public class ConsumableMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumable_id", nullable = false)
    private Long consumableId;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 数量变化：入库为正，出库/报损为负 */
    @Column(name = "qty_change", nullable = false)
    private int qtyChange;

    /** PURCHASE / USE / SCRAP / ADJUST */
    @Column(name = "move_type", nullable = false, length = 16)
    private String moveType;

    /** 定格单价（分）：入库取入库价，出库取当时移动平均成本价 */
    @Column(name = "unit_cost", nullable = false)
    private long unitCost;

    /** 关联业务单号（审批待办号 / 入库批次号）；手工调整为空 */
    @Column(name = "biz_ref", length = 32)
    private String bizRef;

    @Column(nullable = false, length = 64)
    private String operator;

    @Column(length = 255)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private java.time.OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = java.time.OffsetDateTime.now();
    }
}
