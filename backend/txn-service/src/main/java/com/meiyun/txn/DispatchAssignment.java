package com.meiyun.txn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 调度派单占用块（P5-B49 卡12 M1 调度中心）。
 *
 * <p>一条记录 = 某门店某日某资源（DOCTOR 医生 / ROOM 治疗室）在一个 HH:mm 时段上对某张预约的占用。
 * 资源类型/状态为技术码英文存储（前端经字典映射中文，不在界面裸露）；释放采用保留行的状态机
 * （RELEASED + released_at），不物理删除，保证审计与时段历史可追溯。DEVICE 设备无真实台账源，
 * 一期不产生派单（空态 + Backlog）。
 */
@Entity
@Table(name = "dispatch_assignment")
@Getter @Setter @NoArgsConstructor
public class DispatchAssignment {

    /** 资源类型：医生。 */
    public static final String RES_DOCTOR = "DOCTOR";
    /** 资源类型：治疗室。 */
    public static final String RES_ROOM = "ROOM";

    /** 状态：已排期（预约尚未到店）。 */
    public static final String ST_SCHEDULED = "SCHEDULED";
    /** 状态：进行中（预约已到店）。 */
    public static final String ST_IN_PROGRESS = "IN_PROGRESS";
    /** 状态：已释放（保留行，时段回收可再派）。 */
    public static final String ST_RELEASED = "RELEASED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "biz_date", nullable = false)
    private LocalDate bizDate;

    @Column(name = "resource_type", nullable = false, length = 8)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 32)
    private String resourceId;

    /** 资源名称快照（派单时刻的医生姓名/治疗室名），避免后续改名导致历史块失名。 */
    @Column(name = "resource_name", length = 64)
    private String resourceName;

    /** 逻辑关联预约号（appointment.appt_no），不建物理外键，跨服务引用。 */
    @Column(name = "appt_no", nullable = false, length = 24)
    private String apptNo;

    @Column(name = "customer_name", length = 64)
    private String customerName;

    @Column(name = "item_name", length = 64)
    private String itemName;

    @Column(name = "start_time", nullable = false, length = 8)
    private String startTime;                 // HH:mm

    @Column(name = "end_time", nullable = false, length = 8)
    private String endTime;                   // HH:mm

    @Column(nullable = false, length = 16)
    private String status;                    // SCHEDULED / IN_PROGRESS / RELEASED

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
