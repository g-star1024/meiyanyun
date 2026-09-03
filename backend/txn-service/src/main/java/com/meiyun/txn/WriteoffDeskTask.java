package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * M2 划扣核销台任务：待划扣队列的一行。
 *
 * <p>来源两条：
 * <ul>
 *   <li>APPOINTMENT：预约到店签到时自动生成（同一预约幂等仅一条）；</li>
 *   <li>WALKIN：老客未预约直接到店，前台/店长手工建单（新客须先走登记分诊预约，不在此列）。</li>
 * </ul>
 *
 * <p>状态机：PENDING(待执行) → DONE(已划扣，双签 execute)；PENDING → EXCEPTION(异常标记) → PENDING(解除)。
 * DONE 不可标异常、不可解除。双签执行同事务完成卡扣次/扣额并落 writeoff_record。
 * timeline 为 JSON 字符串数组（[{"by":"E005","text":"…","at":"…"}]），随动作追加。
 */
@Entity
@Table(name = "writeoff_desk_task")
@Getter @Setter @NoArgsConstructor
public class WriteoffDeskTask {

    @Id
    @Column(name = "wd_no", length = 24)
    private String wdNo;

    /** 任务状态：PENDING / DONE / EXCEPTION。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 来源：APPOINTMENT(预约到店) / WALKIN(直接到店手工建单)。 */
    @Column(nullable = false, length = 16)
    private String source;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(nullable = false, length = 64)
    private String project;

    /** 建任务时绑定的默认卡（本店在用最新卡）；双签执行时可在卡选择器改卡，执行后回写实际卡。 */
    @Column(name = "card_no", length = 24)
    private String cardNo;

    /** 关联预约号（APPOINTMENT 来源非空，幂等键；WALKIN 为空）。 */
    @Column(name = "appt_no", length = 24)
    private String apptNo;

    /** 本次划扣金额（单位：分），取卡单次均价 floor(balance/remainTimes)。 */
    @Column(nullable = false)
    private Long amount;

    /** 建单人/操作人工号（JWT 登录人，不可信入参一律忽略）。 */
    @Column(nullable = false, length = 32)
    private String operator;

    /** 双签复核人姓名（execute 时必填，自由文本）。 */
    @Column(length = 32)
    private String reviewer;

    /** 异常原因：NONE / CUSTOMER_ABSENT / COUNT_MISMATCH / EQUIPMENT_FAULT / OTHER。 */
    @Column(name = "exception_reason", nullable = false, length = 32)
    private String exceptionReason = "NONE";

    /** 异常补充说明 / 执行备注。 */
    @Column(length = 256)
    private String note;

    /** 预约/到店时间（列表排序与展示用）。 */
    @Column(name = "appointment_time", nullable = false)
    private OffsetDateTime appointmentTime;

    @Column(name = "executed_at")
    private OffsetDateTime executedAt;

    /** 双签执行后生成的划扣记录号（WO…），便于对账追溯。 */
    @Column(name = "writeoff_id", length = 24)
    private String writeoffId;

    /** 时间线 JSON 字符串数组。 */
    @Column(name = "timeline", length = 8192, nullable = false)
    private String timeline;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = "PENDING";
        if (exceptionReason == null) exceptionReason = "NONE";
        if (amount == null) amount = 0L;
    }
}
