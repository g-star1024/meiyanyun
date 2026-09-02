package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 营销活动。
 *
 * 状态机（英文码落库，前端经 label 映射中文）：
 * DRAFT 草稿 → SCHEDULED 待开始 → RUNNING 进行中 → ENDED 已结束；CANCELLED 已取消为旁路终态。
 * 金额（budget/spent/targetAmount/actualAmount）均为 bigint 存分。
 * channels 为渠道中文名 JSON 数组文本（如 ["抖音","小红书"]）。
 */
@Entity
@Table(name = "campaign")
@Getter @Setter @NoArgsConstructor
public class Campaign {

    @Id
    @Column(name = "campaign_id", length = 24)
    private String campaignId;

    @Column(name = "campaign_name", nullable = false, length = 64)
    private String campaignName;

    /** FULL_REDUCE 满减 / DISCOUNT 折扣 / COUPON_PACK 券包 / GIFT 赠品 / NEWBIE 新客礼 / VIP_DAY 会员日。 */
    @Column(name = "campaign_type", nullable = false, length = 16)
    private String campaignType;

    /** DRAFT / SCHEDULED / RUNNING / ENDED / CANCELLED。 */
    @Column(nullable = false, length = 10)
    private String status;

    /** 投放渠道 JSON 数组文本。 */
    @Column(length = 500)
    private String channels;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** 预算（分）。 */
    @Column(nullable = false)
    private Long budget;

    /** 已花费（分）。 */
    @Column(nullable = false)
    private Long spent;

    /** 目标成交额（分）。 */
    @Column(name = "target_amount", nullable = false)
    private Long targetAmount;

    /** 实际成交额（分）。 */
    @Column(name = "actual_amount", nullable = false)
    private Long actualAmount;

    /** 引流新客数。 */
    @Column(name = "new_customers", nullable = false)
    private Integer newCustomers;

    @Column(name = "store_scope", length = 64)
    private String storeScope;

    @Column(length = 32)
    private String owner;

    @Column(length = 500)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
