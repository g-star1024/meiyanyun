package com.meiyun.org;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 请假单（B54 卡5）：最小真实请假域，登记/审批在 org 排班域内闭环（不接 txn 审批中心）。
 *
 * <p>状态机 PENDING → APPROVED / REJECTED 单向终态；批准同事务把区间每日 staff_shift
 * 覆盖为 LEAVE + source=OVERRIDE（派单拒绝在 txn DispatchService 已天然生效），驳回不动排班。
 * 类型中文四态与前端旧契约一致：年假 / 事假 / 病假（换班为历史占位，不接受新登记）。
 */
@Entity
@Table(name = "leave_request",
        uniqueConstraints = @UniqueConstraint(name = "uk_leave_request_no", columnNames = "leave_no"))
@Getter @Setter @NoArgsConstructor
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 持久单号 LV + yyyyMMdd + 三位序号，审计 txnNo 锚点。 */
    @Column(name = "leave_no", nullable = false, length = 24)
    private String leaveNo;

    @Column(name = "staff_id", nullable = false, length = 16)
    private String staffId;

    /** 年假 / 事假 / 病假。 */
    @Column(nullable = false, length = 8)
    private String type;

    @Column(name = "start_date", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    @Column(nullable = false, length = 256)
    private String reason;

    /** PENDING | APPROVED | REJECTED。 */
    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "applied_by", nullable = false, length = 16)
    private String appliedBy;

    @Column(name = "applied_at", nullable = false)
    private OffsetDateTime appliedAt;

    /** 审批人工号（终态后有值）。 */
    @Column(name = "reviewer_id", length = 16)
    private String reviewerId;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    /** 驳回原因（可空）。 */
    @Column(name = "reject_reason", length = 256)
    private String rejectReason;
}
