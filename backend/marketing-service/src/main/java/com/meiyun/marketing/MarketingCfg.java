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
}
