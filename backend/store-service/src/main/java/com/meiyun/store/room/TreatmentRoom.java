package com.meiyun.store.room;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 治疗/咨询/观察/恢复房间档案（B13 门店运营主数据域，DDL §4.1）。
 * 一店一房间码：store_code + room_code 唯一；room_type 为房间用途，status 为房间停用态（一期恒 ACTIVE）。
 * 床位实时占用态（FREE/IN_USE/SANITIZING）不落库，仅床位维护态见 {@link TreatmentBed}。
 */
@Entity
@Table(name = "treatment_room",
        uniqueConstraints = @UniqueConstraint(name = "uk_room_store_code",
                columnNames = {"store_code", "room_code"}))
@Getter
@Setter
@NoArgsConstructor
public class TreatmentRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 门店码（SST01 等）；数据域过滤依据 */
    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 房间编码（门店内唯一，如 A01） */
    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    @Column(nullable = false, length = 64)
    private String name;

    /** 用途：TREATMENT 治疗室 / CONSULT 咨询室 / OBSERVE 观察室 / RECOVERY 恢复室 */
    @Column(name = "room_type", nullable = false, length = 16)
    private String roomType;

    /** 房间状态：ACTIVE 启用 / MAINTENANCE 停用（一期恒 ACTIVE，预留） */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 255)
    private String remark;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private java.time.OffsetDateTime createdAt;

    @Column(name = "updated_by", length = 32)
    private String updatedBy;

    @Column(name = "updated_at")
    private java.time.OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = java.time.OffsetDateTime.now();
        if (status == null) status = "ACTIVE";
    }
}
