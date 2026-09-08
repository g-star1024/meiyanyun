package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 积分规则（B 端配置，单行）。 */
@Entity
@Table(name = "point_rule")
@Getter @Setter @NoArgsConstructor
public class PointRule {

    @Id
    @Column(name = "rule_id")
    private Integer ruleId;

    @Column(name = "earn_rate", nullable = false, precision = 6, scale = 2)
    private BigDecimal earnRate;

    @Column(name = "redeem_ratio", nullable = false, precision = 6, scale = 2)
    private BigDecimal redeemRatio;

    @Column(name = "expire_months", nullable = false)
    private Integer expireMonths;

    /** 每日签到奖励（积分）。 */
    @Column(name = "sign_in_reward")
    private Integer signInReward;

    /** 生日月积分倍率。 */
    @Column(name = "birthday_multiplier", precision = 4, scale = 2)
    private BigDecimal birthdayMultiplier;

    /** 推荐新客奖励（积分）。 */
    @Column(name = "referral_reward")
    private Integer referralReward;

    /** 是否允许手动发放/扣减积分（客户详情页店长调分开关）。 */
    @Column(name = "manual_grant_enabled")
    private Boolean manualGrantEnabled;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
