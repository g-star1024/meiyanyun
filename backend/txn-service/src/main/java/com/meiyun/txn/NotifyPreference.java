package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 员工通知偏好（B21 后端持久化）：替代前端 stores/notification.ts 的本地默认态，
 * 刷新不丢失、跨端一致；{@link ApprovalSlaJob} 落催办通知前按 APPROVAL 类别偏好过滤（关闭则不落 INBOX）。
 *
 * <p>粒度：员工 × 通知类别一行（staff_id + category 唯一）。category 与 notification 表对齐：
 * APPROVAL/CUSTOMER/INVENTORY/MARKETING/SYSTEM；无记录行 = 系统默认（订阅开启、仅站内信）。
 * channels 存逗号串（INBOX/SMS/WECHAT/EMAIL）——渠道位先落库，短信/企微/邮件发送网关在 B22+ 接入，届时不返工表结构。
 */
@Entity
@Table(name = "notify_preference",
        uniqueConstraints = @UniqueConstraint(name = "uk_notify_pref_staff_category",
                columnNames = {"staff_id", "category"}))
@Getter
@Setter
@NoArgsConstructor
public class NotifyPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 员工工号。 */
    @Column(name = "staff_id", nullable = false, length = 64)
    private String staffId;

    /** APPROVAL / CUSTOMER / INVENTORY / MARKETING / SYSTEM。 */
    @Column(nullable = false, length = 16)
    private String category;

    /** 是否订阅该类别（false=免打扰，SLA 催办不落站内信）。 */
    @Column(name = "is_enabled", nullable = false, columnDefinition = "boolean default true")
    private boolean enabled = true;

    /** 接收渠道逗号串：INBOX/SMS/WECHAT/EMAIL（当前仅 INBOX 有发送链路，其余渠道位待 B22+ 网关）。 */
    @Column(columnDefinition = "TEXT")
    private String channels;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() {
        updatedAt = OffsetDateTime.now();
    }
}
