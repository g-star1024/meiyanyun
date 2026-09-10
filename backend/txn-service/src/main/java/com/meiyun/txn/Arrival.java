package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 到店登记（接待台/候诊队列的一行）。
 *
 * <p>来源：WALK_IN（自然到店）/ REFERRAL（转介绍）/ MARKETING（线上营销渠道）/ APPOINTMENT（预约签到自动生成）。
 *
 * <p>状态机对齐前端 stores/arrival.ts 活规格：
 * <pre>
 *   WAITING（候诊中）→ TRIAGED（已分诊·入位）→ CALLED（已叫号）→ DONE（已完成）
 *   WAITING → LEFT（客户离开）
 *   CALLED → TRIAGED（叫号后退回重分诊，后端预留，本批页面不暴露按钮）
 * </pre>
 * queue_no 为门店当日递增排队号（与 AH 单号分离）；appt_no 为预约来源幂等键（先查后插）。
 */
@Entity
@Table(name = "arrival", indexes = {
        @Index(name = "idx_arrival_store_arrived", columnList = "store_code,arrived_at")
})
@Getter @Setter @NoArgsConstructor
public class Arrival {

    @Id
    @Column(name = "ah_no", length = 24)
    private String ahNo;

    /** 状态：WAITING / TRIAGED / CALLED / DONE / LEFT。 */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 到店渠道：WALK_IN / REFERRAL / MARKETING / APPOINTMENT。 */
    @Column(nullable = false, length = 16)
    private String channel;

    /** 门店当日排队号（max+1，与 AH 单号分离）。 */
    @Column(name = "queue_no", nullable = false)
    private Integer queueNo;

    @Column(length = 256)
    private String note;

    /** 关联预约号（APPOINTMENT 来源非空，幂等键）。 */
    @Column(name = "appt_no", length = 24)
    private String apptNo;

    @Column(name = "arrived_at", nullable = false)
    private OffsetDateTime arrivedAt;

    @Column(name = "called_at")
    private OffsetDateTime calledAt;

    @Column(name = "done_at")
    private OffsetDateTime doneAt;

    /** 离开/号源释放时间（WAITING → LEFT；手工释放与超时自动释放同列，原因见审计 payload）。 */
    @Column(name = "left_at")
    private OffsetDateTime leftAt;

    /** 登记/操作人工号（JWT 登录人，不信入参）。 */
    @Column(nullable = false, length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (arrivedAt == null) arrivedAt = OffsetDateTime.now();
        if (status == null) status = "WAITING";
    }
}
