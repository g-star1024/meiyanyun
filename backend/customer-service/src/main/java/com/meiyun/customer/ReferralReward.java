package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 转介绍奖励登记（P5-B86 D4-A：v1 手动确认发放登记；积分/赠金/券自动发放留 v2，挂钩点=idem_key 幂等）。
 * 状态机：PENDING 待发放→GRANTED 已发放 / REJECTED 已驳回。
 * 建表见 V44__referral_baseline.sql。
 */
@Entity
@Table(name = "referral_reward", indexes = {
        @Index(name = "idx_referral_reward_referral", columnList = "referral_id"),
        @Index(name = "idx_referral_reward_status", columnList = "status")
})
@Getter @Setter @NoArgsConstructor
public class ReferralReward {

    /** 奖励单号（主键）：RW + 8 位日期 + '-' + 6 位当日序号（当日 max+1，synchronized 防重）。 */
    @Id
    @Column(name = "reward_id", length = 24)
    private String rewardId;

    /** 所属转介绍单，逻辑引用不建物理 FK。 */
    @Column(name = "referral_id", nullable = false, length = 24)
    private String referralId;

    /** 奖励类型：POINT 积分 / GRANT 赠金 / COUPON 券 / COMMISSION 佣金。 */
    @Column(name = "reward_type", nullable = false, length = 16)
    private String rewardType;

    /** 触发事件：CONFIRMED / VISITED / DEAL。 */
    @Column(name = "trigger_event", nullable = false, length = 16)
    private String triggerEvent;

    /** 金额（分；GRANT/COMMISSION 用，铁律 2）。 */
    @Column(name = "amount_cents")
    private Long amountCents;

    /** 积分（POINT 用）。 */
    @Column(name = "points")
    private Long points;

    /** PENDING 待发放 / GRANTED 已发放 / REJECTED 已驳回。 */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "granted_by", length = 32)
    private String grantedBy;

    @Column(name = "granted_at")
    private OffsetDateTime grantedAt;

    /** 幂等键：referralId:triggerEvent:rewardType（唯一，ApprovalSlaJob idemKey 范式）。 */
    @Column(name = "idem_key", nullable = false, length = 80, unique = true)
    private String idemKey;

    @Column(length = 256)
    private String remark;

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
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
