package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 站内通知（B20 消息通知中心后端落地）：当前唯一生产源为 {@link ApprovalSlaJob} 审批 SLA 超时催办。
 *
 * <p>历史：前端 stores/notification.ts 曾以 seed() 灌 12 条假数据呈现铃铛/通知中心；B20 起由本真表承载，
 * 前端改为拉 GET /api/txn/notifications。字段对齐前端 AppNotification 活规格：
 * category=APPROVAL/CUSTOMER/INVENTORY/MARKETING/SYSTEM；level=INFO/WARNING/URGENT；
 * recipient 为接收人工号（按登录人过滤）；idemKey 唯一索引做催办幂等（同一待办同一轮催办不重复落通知）。
 */
@Entity
@Table(name = "notification",
        indexes = {
                @Index(name = "idx_notification_recipient", columnList = "recipient"),
                @Index(name = "uk_notification_idem", columnList = "idem_key", unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 接收人工号（通知中心按登录人 recipient 过滤）。 */
    @Column(nullable = false, length = 64)
    private String recipient;

    /** APPROVAL / CUSTOMER / INVENTORY / MARKETING / SYSTEM（当前仅生产 APPROVAL）。 */
    @Column(nullable = false, length = 16)
    private String category;

    /** INFO / WARNING / URGENT（超时催办 URGENT）。 */
    @Column(nullable = false, length = 8)
    private String level;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(nullable = false, length = 500)
    private String content;

    /** 点击跳转前端路由（如 /approval）；可空。 */
    @Column(length = 128)
    private String link;

    /** 关联业务单号（AP 待办号 / RF / CC 等），可空。 */
    @Column(name = "biz_ref", length = 32)
    private String bizRef;

    /** 发送方（系统催办为 system）。 */
    @Column(length = 64)
    private String sender;

    /** 幂等键：同一催办轮次同一接收人唯一（如 SLA:AP20260908-000001:SE001:1），重复落库冲突跳过。 */
    @Column(name = "idem_key", nullable = false, length = 128)
    private String idemKey;

    @Column(name = "is_read", nullable = false, columnDefinition = "boolean default false")
    private boolean read = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
