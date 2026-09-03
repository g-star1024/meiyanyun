package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** 营销全局配置（老带新奖励/提成比例/周触达上限）。 */
@Entity
@Table(name = "marketing_cfg")
@Getter @Setter @NoArgsConstructor
public class MarketingCfg {

    @Id
    @Column(name = "cfg_id")
    private Integer cfgId;

    @Column(name = "referral_arrived_reward", nullable = false)
    private Integer referralArrivedReward;

    @Column(name = "referral_deal_reward", nullable = false)
    private Integer referralDealReward;

    @Column(name = "commission_rate", nullable = false, precision = 4, scale = 2)
    private BigDecimal commissionRate;

    @Column(name = "weekly_push_limit", nullable = false)
    private Integer weeklyPushLimit;

    // ==================== M5-15 营销设置（免打扰 / 审批流 / 默认渠道） ====================
    // 新列不加 nullable=false：ddl-auto=update 补列时旧行可为空，空值按默认口径回落。

    /** 营销免打扰时段开关。 */
    @Column(name = "quiet_hours_enabled")
    private Boolean quietHoursEnabled;

    /** 免打扰开始时间 HH:mm。 */
    @Column(name = "quiet_start", length = 5)
    private String quietStart;

    /** 免打扰结束时间 HH:mm。 */
    @Column(name = "quiet_end", length = 5)
    private String quietEnd;

    /** 节日豁免开关（免打扰时段在节假日不生效）。 */
    @Column(name = "holiday_exempt")
    private Boolean holidayExempt;

    /** 大额券审批阈值（bigint 存「分」）。 */
    @Column(name = "large_coupon_threshold_fen")
    private Long largeCouponThresholdFen;

    /** 推送需审批开关。 */
    @Column(name = "push_requires_approval")
    private Boolean pushRequiresApproval;

    /** 审批层级：1=单级审批，2=两级审批。 */
    @Column(name = "approval_level")
    private Integer approvalLevel;

    /** 默认推送渠道（JSON 数组文本，元素 ∈ SMS/WECOM/WECHAT_MP）。 */
    @Column(name = "default_push_channels", length = 128)
    private String defaultPushChannels;

    /** 默认投放渠道（JSON 数组文本，元素 ∈ 六渠道白名单）。 */
    @Column(name = "default_ad_channels", length = 256)
    private String defaultAdChannels;
}
