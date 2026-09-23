package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 复诊召回（P5-B90，/recall 切真）。
 * 状态机（服务端唯一权威，非法转移 409）：
 * PENDING → [NOTIFIED, SKIPPED]；NOTIFIED → [CONFIRMED, BOOKED, SKIPPED, PENDING 改期]；
 * CONFIRMED → [BOOKED, SKIPPED]；BOOKED / SKIPPED 终态。
 * 四来源：DOCTOR_ADVICE 医嘱 / COURSE_FOLLOW 疗程跟进 / SYSTEM_AUTO 系统自动 / MANUAL 人工。
 * 四方式：PHONE 电话 / WECHAT 企微 / SMS 短信 / IN_STORE 到店。
 * timeline 为 JSONB 文本 [{at,by,action,detail}]，每次状态转移 append 一条。
 * 与 txn followup 表零共享（D5 诊疗边界）。
 */
@Entity
@Table(name = "recall")
@Getter @Setter @NoArgsConstructor
public class Recall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 召回单号：RC + yyyyMMdd + - + 6 位当日序号（BizNoGenerator）。 */
    @Column(name = "recall_no", nullable = false, unique = true, length = 32)
    private String recallNo;

    @Column(name = "customer_id", length = 16)
    private String customerId;

    /** 客户姓名冗余列：创建时经 CustomerDirectoryClient 解析落列。 */
    @Column(name = "customer_name", length = 64)
    private String customerName;

    /** DOCTOR_ADVICE / COURSE_FOLLOW / SYSTEM_AUTO / MANUAL。 */
    @Column(nullable = false, length = 16)
    private String source;

    /** 召回事由。 */
    @Column(nullable = false, length = 200)
    private String reason;

    /** 关联病历号（可空，逻辑引用不建外键）。 */
    @Column(name = "related_emr_no", length = 32)
    private String relatedEmrNo;

    /** 关联订单号（可空，逻辑引用不建外键）。 */
    @Column(name = "related_order_no", length = 32)
    private String relatedOrderNo;

    /** 末次到店 / 治疗日。 */
    @Column(name = "last_visit_date")
    private LocalDate lastVisitDate;

    /** 应召回日（超期 / 今日待提醒 / 3 日到期 KPI 锚，业务时区 +8）。 */
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** PHONE / WECHAT / SMS / IN_STORE。 */
    @Column(nullable = false, length = 16)
    private String method;

    /** PENDING / NOTIFIED / CONFIRMED / BOOKED / SKIPPED。 */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    /** 提醒操作人（B90 卡1 增补：前端 notifiedByName 契约直出，避免 timeline 解析）。 */
    @Column(name = "notified_by", length = 32)
    private String notifiedBy;

    /** 提醒时间（B90 卡1 增补：前端 notifiedAt 契约直出）。 */
    @Column(name = "notified_at")
    private OffsetDateTime notifiedAt;

    /** 客户回复。 */
    @Column(name = "customer_reply", length = 500)
    private String customerReply;

    /** 确认复诊日。 */
    @Column(name = "confirmed_date")
    private LocalDate confirmedDate;

    /** 跳过原因（B90 卡1 增补：前端 skipReason 契约直出）。 */
    @Column(name = "skip_reason", length = 200)
    private String skipReason;

    @Column(length = 500)
    private String note;

    /** 时间线 JSONB：[{at,by,action,detail}]，每次状态转移 append 一条。 */
    @Column(columnDefinition = "jsonb")
    private String timeline;

    /** 来源规则：逻辑引用 automation_rule.rule_no，NULL=人工创建（SYSTEM_AUTO 来源时非空）。 */
    @Column(name = "rule_no", length = 32)
    private String ruleNo;

    /** 门店码：NULL=全连锁，填值=客户归属门店（铁律-1-D）。 */
    @Column(name = "store_code", length = 32)
    private String storeCode;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
