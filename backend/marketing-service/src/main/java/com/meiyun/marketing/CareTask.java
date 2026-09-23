package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 关怀任务（P5-B90，/m3-care 切真）。
 * 四型：BIRTHDAY 生日 / HOLIDAY 节日 / REPURCHASE 复购 / REACTIVATE 唤醒。
 * 三渠道：SMS 短信 / WECHAT 企微（推送落库映射 WECOM）/ PHONE 电话（仅登记 sent_at 不调推送）。
 * 三态：PENDING 待关怀 → SENT 已发送 → REACHED 已触达。
 * send 经 PushService consent 门控：撤回/未授权→skipped 不推进状态、不落 record（合规优先）。
 * rule_no NULL=人工创建，非空=Flow 引擎生成（前端展示「自动」徽标）。
 */
@Entity
@Table(name = "care_task")
@Getter @Setter @NoArgsConstructor
public class CareTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 关怀单号：CARE + yyyyMMdd + - + 6 位当日序号（BizNoGenerator）。 */
    @Column(name = "care_no", nullable = false, unique = true, length = 32)
    private String careNo;

    @Column(name = "customer_id", length = 16)
    private String customerId;

    /** 客户姓名冗余列：创建时经 CustomerDirectoryClient 解析落列。 */
    @Column(name = "customer_name", length = 64)
    private String customerName;

    /** BIRTHDAY / HOLIDAY / REPURCHASE / REACTIVATE。 */
    @Column(nullable = false, length = 16)
    private String type;

    /** SMS / WECHAT / PHONE。 */
    @Column(nullable = false, length = 16)
    private String channel;

    /** 关怀文案（经 ForbiddenWordService 校验）。 */
    @Column(length = 500)
    private String content;

    /** 计划关怀日（KPI「本月待关怀」锚，业务时区 +8）。 */
    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    /** PENDING / SENT / REACHED。 */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    /** 触达标记。 */
    @Column(nullable = false)
    private Boolean reached = Boolean.FALSE;

    /** 带来预约标记（KPI「带来预约」锚）。 */
    @Column(name = "converted_booking", nullable = false)
    private Boolean convertedBooking = Boolean.FALSE;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "reached_at")
    private OffsetDateTime reachedAt;

    /** 来源规则：逻辑引用 automation_rule.rule_no，NULL=人工创建。 */
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
