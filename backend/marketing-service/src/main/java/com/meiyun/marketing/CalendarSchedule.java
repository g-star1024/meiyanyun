package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 日历活动排期（P5-B88）。
 *
 * 状态机（英文码落库，前端经 label 映射中文）：
 * DRAFT 草稿 → SCHEDULED 待开始 → RUNNING 进行中 → ENDED 已结束；
 * SCHEDULED/RUNNING 由 CalendarScheduleJob 按日期日更推进（过期未启动直接 ENDED）。
 * estimated_revenue_cents bigint 存「分」（铁律 2，前端活规格为元，×100 在适配层）。
 * store_code NULL=全连锁、填值=单店（D2-A）。
 * coupon_ids / channels 为 JSON 数组文本；node_id 逻辑引用 calendar_node.node_id（不建物理外键）。
 */
@Entity
@Table(name = "calendar_schedule")
@Getter @Setter @NoArgsConstructor
public class CalendarSchedule {

    @Id
    @Column(name = "schedule_id", length = 24)
    private String scheduleId;

    /** 逻辑引用 calendar_node.node_id。 */
    @Column(name = "node_id", nullable = false, length = 24)
    private String nodeId;

    /** 节点日期冗余，便于按日查询。 */
    @Column(name = "node_date", nullable = false)
    private LocalDate nodeDate;

    @Column(name = "schedule_name", nullable = false, length = 64)
    private String scheduleName;

    @Column(name = "benefit_desc", length = 256)
    private String benefitDesc;

    /** 关联券模板号 JSON 数组文本。 */
    @Column(name = "coupon_ids")
    private String couponIds;

    @Column(name = "points_reward", nullable = false)
    private Integer pointsReward;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** 推送渠道 JSON 数组文本：SMS 短信 / WECOM 企微 / WECHAT_MP 公众号。 */
    @Column
    private String channels;

    @Column(name = "copy_text", length = 500)
    private String copyText;

    /** DRAFT / SCHEDULED / RUNNING / ENDED。 */
    @Column(nullable = false, length = 10)
    private String status;

    /** 预估成交额（分）。 */
    @Column(name = "estimated_revenue_cents", nullable = false)
    private Long estimatedRevenueCents;

    /** 门店码：NULL=全连锁，填值=单店排期。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    /** 创建幂等令牌（前端表单会话生成）。 */
    @Column(name = "client_token", length = 64)
    private String clientToken;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
