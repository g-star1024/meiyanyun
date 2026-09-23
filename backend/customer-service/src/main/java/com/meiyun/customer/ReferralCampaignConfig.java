package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 邀请机制全局配置（P5-B89，单行 GLOBAL，point_rule 先例）。
 * ladders/levels 为 JSON 文本落 TEXT 列（MemberLevel.benefits 全仓先例）：
 * ladders=[{threshold,type,amount,desc}] amount 口径为元；levels=[{level,rate,desc}] rate 0~1。
 * D2：v1 仅配置持久化，奖励自动发放链路留 v2。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "referral_campaign_config")
public class ReferralCampaignConfig {

    /** 单行全局配置主键，恒 'GLOBAL'（V47 chk_rcc_config_id 约束）。 */
    @Id
    @Column(name = "config_id", length = 16)
    private String configId;

    /** 基础奖励形式（前端词表）：POINTS 积分 / COUPON 优惠券 / CASH 现金。 */
    @Column(name = "reward_type", nullable = false, length = 16)
    private String rewardType;

    /** 邀请有效期（天），>=1。 */
    @Column(name = "valid_days", nullable = false)
    private Integer validDays;

    /** 邀请话术。 */
    @Column(nullable = false, columnDefinition = "text")
    private String script;

    /** 阶梯奖励 JSON 文本：[{threshold,type,amount,desc}]。 */
    @Column(nullable = false, columnDefinition = "text")
    private String ladders;

    /** 层级奖励 JSON 文本：[{level,rate,desc}]，rate 0~1。 */
    @Column(nullable = false, columnDefinition = "text")
    private String levels;

    @Column(name = "updated_by", length = 32)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (rewardType == null) rewardType = "CASH";
        if (validDays == null) validDays = 30;
        if (script == null) script = "";
        if (ladders == null) ladders = "[]";
        if (levels == null) levels = "[]";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
