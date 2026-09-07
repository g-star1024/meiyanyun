package com.meiyun.store.equipment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 设备校准/维保/维修记录（B13）：equipment 的子表明细。
 *
 * <p>type：CALIBRATION 校准 / MAINTENANCE 维保 / REPAIR 维修。
 * 新增记录时业务规则（与前端 mock 同构）：CALIBRATION+nextAt 回写下次校准日；
 * MAINTENANCE/REPAIR+nextAt 回写下次维保日；CALIBRATION 且设备 CALIBRATING → 复位 NORMAL；
 * REPAIR 且 REPAIRING → 复位 NORMAL；cost 累加折旧（封顶购置金额）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "equipment_maintenance")
public class EquipmentMaintenance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    /** CALIBRATION/MAINTENANCE/REPAIR */
    @Column(nullable = false, length = 16)
    private String type;

    /** 发生日期（精确到天） */
    @Column(name = "occurred_at", nullable = false)
    private LocalDate occurredAt;

    /** 经办人（前端 by） */
    @Column(nullable = false, length = 32)
    private String actor;

    /** 服务商 / 工程师 */
    @Column(length = 64)
    private String vendor;

    @Column(nullable = false, length = 255)
    private String summary;

    /** 本次之后的下次日期 */
    @Column(name = "next_at")
    private LocalDate nextAt;

    /** 费用（分），无费用记 0 */
    @Column(name = "cost_fen", nullable = false)
    private Long costFen;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (costFen == null) costFen = 0L;
    }
}
