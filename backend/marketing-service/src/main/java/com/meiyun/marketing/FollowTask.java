package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 跟进任务（M3-B2 / DESIGN-M3 §3 D1，/m3-follow-tasks 切真 + AI 干预下发 D3-1/2）。
 *
 * <p>type 六值：PHONE 电话 / WECHAT 企微 / IN_STORE 到店 / BIRTHDAY 生日 / POST_OP 术后 / CONTENT 内容触达。
 * status 三态：PENDING 待跟进 / DONE 已完成 / OVERDUE 已逾期——<b>OVERDUE 为查询侧推导</b>
 * （deadline&lt;now 且 PENDING 即视为逾期），库内仅落 PENDING/DONE，零定时刷写任务。
 *
 * <p>source 三值：MANUAL 人工创建 / CHURN 流失预警下发 / REPURCHASE 复购预警下发。
 * AI 下发幂等：idem_key = source:sourceId:customerId:date（D8），部分唯一索引（V55 照 V52 先例），
 * 重放/并发撞唯一约束 → 服务层捕 DataIntegrityViolationException 直返既有任务，不重复建单。
 *
 * <p>store_code NULL=全连锁（铁律-1-D）。列映射与 V55 DDL 逐列对齐（ddl-auto=validate 硬约束）。
 */
@Entity
@Table(name = "follow_task")
@Getter @Setter @NoArgsConstructor
public class FollowTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务单号（FT+yyyyMMdd-6 位，BizNoGenerator）。 */
    @Column(name = "follow_no", nullable = false, unique = true, length = 32)
    private String followNo;

    /** 客户号（可空：匿名/未建档客户仅记姓名）。 */
    @Column(name = "customer_id", length = 16)
    private String customerId;

    /** 客户姓名（冗余，列表免 join）。 */
    @Column(name = "customer_name", nullable = false, length = 64)
    private String customerName;

    /** 客户等级（自由文本：钻石/白金/黄金/金卡/银卡/普通…）。 */
    @Column(name = "customer_level", length = 16)
    private String customerLevel;

    /** 任务类型：PHONE/WECHAT/IN_STORE/BIRTHDAY/POST_OP/CONTENT。 */
    @Column(nullable = false, length = 16)
    private String type;

    /** 任务内容。 */
    @Column(length = 500)
    private String content;

    /** 截止时间（逾期推导基准）。 */
    private OffsetDateTime deadline;

    /** 状态：PENDING/DONE（OVERDUE 查询侧推导，不落库）。 */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    /** 优先级：HIGH/MEDIUM/LOW。 */
    @Column(nullable = false, length = 8)
    private String priority = "MEDIUM";

    /** 跟进人姓名（AI 下发=系统派发）。 */
    @Column(length = 64)
    private String assignee;

    /** 完成时刻（DONE 时落）。 */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** 跟进日志 JSON 数组（最新在前；jsonb 映射照 TouchEvent.payload 先例）。 */
    @Column(columnDefinition = "jsonb")
    private String logs;

    /** 来源：MANUAL/CHURN/REPURCHASE。 */
    @Column(nullable = false, length = 16)
    private String source = "MANUAL";

    /** 来源单据号（AI 预测 id 等）。 */
    @Column(name = "source_id", length = 64)
    private String sourceId;

    /** 幂等键（AI 下发专用；MANUAL 为 NULL 不参与唯一约束）。 */
    @Column(name = "idem_key", length = 128)
    private String idemKey;

    /** 归属门店编码；NULL=全连锁（铁律-1-D）。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    /** 创建人工号（AI 下发=system）。 */
    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "PENDING";
        if (priority == null) priority = "MEDIUM";
        if (source == null) source = "MANUAL";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
