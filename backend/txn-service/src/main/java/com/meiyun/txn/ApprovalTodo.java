package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 统一审批待办（T3-01 审批中心）：聚合全平台八类双签业务的审批任务。
 * 单号 AP+yyyyMMdd+'-'+6 位当日序号；bizNo 指向业务单号（RF/CC 退款退卡、AT/LV/PO/PC/LS/RQ 等）。
 * 对齐前端 stores/approval.ts 活规格：stage=REVIEW（店长/运营一审）/ FINANCE（财务复核，L1 直达）；
 * status=PENDING/APPROVED/REJECTED/TRANSFERRED；history 为 JSON 数组字符串（TEXT 落库）。
 * REFUND/CARD_CANCEL 的审批动作经 ApprovalService 回写 TxnService 状态机形成闭环。
 */
@Entity
@Table(name = "approval_todo")
@Getter
@Setter
@NoArgsConstructor
public class ApprovalTodo {

    @Id
    @Column(name = "todo_no", length = 24)
    private String todoNo;            // AP2026xxxx-xxx

    /** 业务类型：REFUND/CARD_CANCEL/TRANSFER/LEAVE/PROCUREMENT/PRICE_CHANGE/LOSS_REPORT/REQUISITION。 */
    @Column(name = "biz_type", nullable = false, length = 16)
    private String bizType;

    /** 关联业务单号（退款 RF / 退卡 CC 等），也是回写业务状态机的定位键。 */
    @Column(name = "biz_no", nullable = false, length = 24)
    private String bizNo;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(nullable = false, length = 255)
    private String summary;

    /** 审批金额（分）；无金额业务为空。 */
    @Column(name = "amount")
    private Long amount;

    @Column(nullable = false, length = 64)
    private String applicant;

    @Column(name = "applicant_role", length = 32)
    private String applicantRole;

    /** 签署层级 L1/L2/L3（决定起始阶段与优先级默认值）。 */
    @Column(name = "sign_tier", nullable = false, length = 2)
    private String signTier;

    /** PENDING / APPROVED / REJECTED / TRANSFERRED。 */
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'PENDING'")
    private String status = "PENDING";

    /** 当前阶段：REVIEW=店长/运营一审；FINANCE=财务复核。 */
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'REVIEW'")
    private String stage = "REVIEW";

    /** HIGH / MEDIUM / LOW。 */
    @Column(nullable = false, length = 8, columnDefinition = "varchar(8) default 'MEDIUM'")
    private String priority = "MEDIUM";

    /** 数据域门店码（提交时按业务单回填；数据权限过滤/详情断言依据）。 */
    @Column(name = "store_code", length = 32)
    private String storeCode;

    @Column(name = "store_name", length = 64)
    private String storeName;

    /** 当前指派人（转交后变化）；空表示按角色自动路由。 */
    @Column(length = 64)
    private String assignee;

    /** 加签人列表（逗号分隔；前端适配层拆为数组）。 */
    @Column(name = "co_signers", columnDefinition = "TEXT")
    private String coSigners;

    /** 审批留痕：JSON 数组 [{actor,action,comment,at},...]（TEXT 落库，前端适配层解析）。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String history = "[]";

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (submittedAt == null) submittedAt = now;
    }
}
