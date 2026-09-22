package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * M3-21 转介绍关系单（P5-B86：老客推荐新客关系链）。
 * 状态机：PENDING→CONFIRMED→VISITED→DEAL；PENDING→REJECTED；PENDING/CONFIRMED→EXPIRED（ReferralExpireJob）。
 * D3-A：EXPIRED/REJECTED 释放被推荐人绑定可重新推荐（V44 部分唯一索引 uk_referral_referee_active 兜底）。
 * 建表见 V44__referral_baseline.sql（Flyway 全列定义幂等可重入，ddl-auto=update no-op）。
 */
@Entity
@Table(name = "referral", indexes = {
        @Index(name = "idx_referral_referrer", columnList = "referrer_customer_id"),
        @Index(name = "idx_referral_status", columnList = "status"),
        @Index(name = "idx_referral_store", columnList = "store_code"),
        @Index(name = "idx_referral_expire", columnList = "expire_at")
})
@Getter @Setter @NoArgsConstructor
public class Referral {

    /** 关系单号（主键）：RF + 8 位日期 + '-' + 6 位当日序号（当日 max+1，synchronized 防重，铁律 6）。 */
    @Id
    @Column(name = "referral_id", length = 24)
    private String referralId;

    /** 推荐人（老客）customer_id，逻辑引用不建物理 FK（本服务 convention）。 */
    @Column(name = "referrer_customer_id", nullable = false, length = 16)
    private String referrerCustomerId;

    /** 被推荐人（新客）customer_id，逻辑引用不建物理 FK。 */
    @Column(name = "referee_customer_id", nullable = false, length = 16)
    private String refereeCustomerId;

    /** 活动 id 预留（v2 活动配置接入），现恒空。 */
    @Column(name = "campaign_id", length = 24)
    private String campaignId;

    /** PENDING/CONFIRMED/VISITED/DEAL/EXPIRED/REJECTED（REFERRAL_STATUS 字典）。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 有效期天数（默认 30，对齐前端活动配置 validDays=30）。 */
    @Column(name = "valid_days", nullable = false)
    private Integer validDays = 30;

    /** 绑定时间（创建即绑定）。 */
    @Column(name = "bound_at", nullable = false)
    private OffsetDateTime boundAt;

    /** 到期时间（落库即算 = bound_at + valid_days，ReferralExpireJob 扫描依据）。 */
    @Column(name = "expire_at", nullable = false)
    private OffsetDateTime expireAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "visited_at")
    private OffsetDateTime visitedAt;

    @Column(name = "deal_at")
    private OffsetDateTime dealAt;

    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;

    @Column(name = "rejected_at")
    private OffsetDateTime rejectedAt;

    @Column(name = "reject_reason", length = 128)
    private String rejectReason;

    /** 成交金额（分，铁律 2）。 */
    @Column(name = "deal_amount_cents")
    private Long dealAmountCents;

    /** 归属门店（数据域隔离）。 */
    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(length = 256)
    private String remark;

    /** 创建人（DataScope actor）。 */
    @Column(name = "created_by", length = 32)
    private String createdBy;

    /** 前端幂等键（重放返回既有单，不重复落库）。 */
    @Column(name = "client_token", length = 64)
    private String clientToken;

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
        if (validDays == null) validDays = 30;
        if (boundAt == null) boundAt = now;
        if (expireAt == null) expireAt = boundAt.plusDays(validDays);
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
