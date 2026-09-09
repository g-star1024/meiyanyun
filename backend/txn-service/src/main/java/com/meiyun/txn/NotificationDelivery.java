package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 通知渠道投递记录（域⑦ 通知多渠道扇出）。
 *
 * <p>notification 表行本身即「站内信(INBOX)」载体；本表记录同一条通知按收件人偏好扇出到
 * SMS/WECHAT/EMAIL 等渠道的投递状态，便于审计与失败重试。status ∈
 * PENDING/SENT/FAILED/SKIPPED/DEFERRED/DEAD（DEFERRED=免打扰时段延后，DEAD=重试上限或确定性拒绝，
 * 不再自动投递，留待人工）。
 * 与 notification 表通过 notification_id 逻辑关联（不建外键，避免改动既有 notification 表结构）。
 */
@Entity
@Table(name = "notification_delivery",
        uniqueConstraints = @UniqueConstraint(name = "uk_nd_notif_channel",
                columnNames = {"notification_id", "channel"}),
        indexes = @Index(name = "idx_nd_status", columnList = "status"))
@Getter
@Setter
@NoArgsConstructor
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联 notification.id（逻辑关联，无外键）。 */
    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    /** 渠道码：INBOX / SMS / WECHAT / EMAIL。 */
    @Column(nullable = false, length = 16)
    private String channel;

    /** PENDING / SENT / FAILED / SKIPPED / DEFERRED / DEAD。 */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, columnDefinition = "int default 0")
    private int attemptCount;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    /** 最早可重试时间（指数退避）；null 表示立即可取（含 PENDING 残行）。 */
    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    /** 发往渠道网关的请求体快照（JSON 文本），便于排障。 */
    @Column(columnDefinition = "TEXT")
    private String payload;
}
