package com.meiyun.store.equipment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 设备仪器台账（B13）：激光/射频/超声/注射等医美仪器主数据 + 折旧/校准维保临期。
 *
 * <p>状态四态全部真实持久化：NORMAL 正常 / CALIBRATING 校准中 / REPAIRING 维修中 / DISABLED 停用。
 * 金额一律 Long「分」落库（purchase_amount_fen / depreciated_fen），出参转「元」。
 * 日期（购置/下次校准/下次维保）按 LocalDate 落库，仅精确到天。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "equipment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_eq_store_asset", columnNames = {"store_code", "asset_no"})
})
public class Equipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 资产编号，店内唯一 */
    @Column(name = "asset_no", nullable = false, length = 32)
    private String assetNo;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 64)
    private String brand;

    @Column(length = 64)
    private String model;

    /** LASER/RF/ULTRASOUND/INJECTION/MONITOR/OTHER */
    @Column(nullable = false, length = 16)
    private String category;

    @Column(nullable = false, length = 64)
    private String location;

    /** NORMAL/CALIBRATING/REPAIRING/DISABLED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "purchased_at", nullable = false)
    private LocalDate purchasedAt;

    /** 购置金额（分） */
    @Column(name = "purchase_amount_fen", nullable = false)
    private Long purchaseAmountFen;

    @Column(name = "lifespan_years", nullable = false)
    private Integer lifespanYears;

    /** 已累计折旧/维保费用（分），封顶购置金额 */
    @Column(name = "depreciated_fen", nullable = false)
    private Long depreciatedFen;

    @Column(name = "next_calibration_at")
    private LocalDate nextCalibrationAt;

    @Column(name = "next_maintenance_at")
    private LocalDate nextMaintenanceAt;

    @Column(length = 255)
    private String note;

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
        if (status == null) status = "NORMAL";
        if (depreciatedFen == null) depreciatedFen = 0L;
    }
}
