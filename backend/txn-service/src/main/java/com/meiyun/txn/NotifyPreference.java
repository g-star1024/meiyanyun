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
 * channels 存逗号串（INBOX/SMS/WECHAT/EMAIL）——B26/B57 三外部渠道网关已全量落地，
 * 未配置网关时投递诚实落 SKIPPED。
 *
 * <p>B60 增个人级免打扰时段（L65）：quietEnabled + quietStart/quietEnd（HH:mm，可跨午夜）。
 * 语义与全局 {@link NotificationQuietConfig} 对齐——仅延后非 INBOX 渠道、URGENT 恒豁免；
 * 业务表由 JPA ddl-auto=update 自动加列，无需 Flyway。
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

    /** 接收渠道逗号串：INBOX/SMS/WECHAT/EMAIL（四渠道均已落地，未配网关的外部渠道投递落 SKIPPED）。 */
    @Column(columnDefinition = "TEXT")
    private String channels;

    /** 个人免打扰开关（仅对非 INBOX 渠道生效，URGENT 恒豁免）。 */
    @Column(name = "quiet_enabled", nullable = false, columnDefinition = "boolean default false")
    private boolean quietEnabled = false;

    /** 个人免打扰开始时刻 HH:mm（可跨午夜，如 22:00）。 */
    @Column(name = "quiet_start", length = 8)
    private String quietStart;

    /** 个人免打扰结束时刻 HH:mm（可跨午夜，如次日 08:00）。 */
    @Column(name = "quiet_end", length = 8)
    private String quietEnd;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() {
        updatedAt = OffsetDateTime.now();
    }
}
