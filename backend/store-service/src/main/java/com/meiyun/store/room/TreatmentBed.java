package com.meiyun.store.room;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 床位档案（B13，DDL §4.2）。
 * 一店一床位码：store_code + bed_code 唯一；room_id 为逻辑外键（treatment_room.id）。
 * 实时交易态（FREE 空闲 / IN_USE 使用中 / SANITIZING 消毒中）一期不持久化（刷新复位，前端演示态）；
 * 仅 maint_status 维护停用态落库：OK 正常 / MAINTENANCE 维护中，maint_reason 记维护原因。
 */
@Entity
@Table(name = "treatment_bed",
        uniqueConstraints = @UniqueConstraint(name = "uk_bed_store_code",
                columnNames = {"store_code", "bed_code"}))
@Getter
@Setter
@NoArgsConstructor
public class TreatmentBed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 所属房间（treatment_room.id，逻辑外键） */
    @Column(name = "room_id", nullable = false)
    private Long roomId;

    /** 床位编码（门店内唯一，种子如 A01-1，新建房间生成 {roomCode}-B{n}） */
    @Column(name = "bed_code", nullable = false, length = 32)
    private String bedCode;

    /** 维护态：OK 正常 / MAINTENANCE 维护中（唯一持久化的床位状态） */
    @Column(name = "maint_status", nullable = false, length = 16)
    private String maintStatus;

    /** 维护原因（设为维护时填写，恢复后清空） */
    @Column(name = "maint_reason", length = 255)
    private String maintReason;

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
        if (maintStatus == null) maintStatus = "OK";
    }
}
