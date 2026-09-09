package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 外部广告渠道回传记录（域⑤ 外部渠道 A：回传接入骨架）。
 *
 * <p>各广告平台（抖音/小红书/美团等）把点击/线索/转化事件回传至
 * POST /api/marketing/channels/{channelCode}/callback；本表落原始回调体供对账与后续转化归因。
 * 真实 OAuth/签名校验仅留接入位（dev-no-auth 默认 true 便于种子联调，生产置 false 并配密钥）。
 *
 * <p>event_type ∈ CLICK / LEAD / CONVERSION；status ∈ RECEIVED（已收） / PROCESSED（已归因匹配）。
 * 本期只做「接收 + 落库 + 种子联调」，转化归因与券核销回写列为下游 Backlog。
 */
@Entity
@Table(name = "channel_returnback",
        uniqueConstraints = @UniqueConstraint(name = "uk_crb_chan_bizref",
                columnNames = {"channel_code", "biz_ref"}),
        indexes = {
                @Index(name = "idx_crb_channel", columnList = "channel_code"),
                @Index(name = "idx_crb_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
public class ChannelReturnback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_code", nullable = false, length = 32)
    private String channelCode; // DOUYIN / RED / MEITUAN / ...

    @Column(name = "external_user_id", length = 64)
    private String externalUserId;

    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType; // CLICK / LEAD / CONVERSION

    @Column(name = "biz_ref", length = 64)
    private String bizRef;

    /** 回调签名随机串（X-Channel-Nonce），配合 (channel_code,biz_ref) 与时间戳窗口防重放。 */
    @Column(name = "sig_nonce", length = 64)
    private String sigNonce;

    /** 原始回调体 JSON 快照。 */
    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, length = 16)
    private String status; // RECEIVED / PROCESSED

    @Column(name = "matched_customer_id", length = 64)
    private String matchedCustomerId;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @PrePersist
    void prePersist() {
        if (receivedAt == null) receivedAt = OffsetDateTime.now();
        if (status == null) status = "RECEIVED";
    }
}
