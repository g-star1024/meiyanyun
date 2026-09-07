package com.meiyun.store.room;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 房间/床位操作日志（B13，DDL §4.3）。
 * 仅记录真实落库的主数据动作：ADD_ROOM 建房 / SET_MAINTENANCE 设维护 / RESTORE 维护恢复；
 * 入住/退房/消毒等交易态演示动作不写本表（不持久化）。
 */
@Entity
@Table(name = "room_operation_log")
@Getter
@Setter
@NoArgsConstructor
public class RoomOperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "room_code", length = 32)
    private String roomCode;

    @Column(name = "bed_code", length = 32)
    private String bedCode;

    /** 动作：ADD_ROOM / SET_MAINTENANCE / RESTORE */
    @Column(nullable = false, length = 32)
    private String action;

    @Column(length = 255)
    private String text;

    /** 操作人（staffName） */
    @Column(length = 32)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private java.time.OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = java.time.OffsetDateTime.now();
    }
}
