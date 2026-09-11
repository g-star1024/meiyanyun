package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 客户检索索引同步事件本地 outbox（B30 建档实时写 ES，业务表 ddl-auto）。
 *
 * <p>outbox 模式（与 card_finance_event / finance_event 同构）：建档业务事务内同写本事件表（PENDING），
 * payload 只存 customerId——文档内容一律由中继任务回查 PG 权威数据组装，避免事件里快照过期/字段漂移；
 * {@code CustomerSearchEventRelayJob} 近线扫描并以 _id=customerId 幂等 upsert 进 ES。
 * ES 不可用不影响建档主流程：投递失败仅重试/告警。
 *
 * <p>状态机：PENDING（待投递）→ SENT（ES 已受理）；DEAD（ES 4xx 确定性拒绝，如 mapping 冲突，
 * 不再重试，留待人工排查）；DISCARDED（人工确认丢弃的终态，如客户已物理删除/永久毒消息，
 * 不再自动投递也不再重试）。DEAD 事件在「检索事件处置台」可人工重试（回 PENDING 交中继）、
 * 立即重放（同步 upsert 出结果）或丢弃（置 DISCARDED 并留原因/操作人/时间）。
 */
@Entity
@Table(name = "customer_search_event")
@Getter
@Setter
@NoArgsConstructor
public class CustomerSearchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    /** 事件类型：UPSERT（客户建档/变更后写入索引）。 */
    @Column(name = "event_type", nullable = false, length = 16)
    private String eventType = "UPSERT";

    /** 客户号（PG customer 主键）；中继时回查 PG 组装 ES 文档，事件本身不存快照。 */
    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    /** PENDING / SENT / DEAD / DISCARDED。 */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /** 最近一次投递失败原因（截断 512 字），成功后清空。 */
    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    /** 人工处置时间（重试成功/重放成功/丢弃均写），与中继自动投递相区分。 */
    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    /** 人工处置人工号（DataScope.currentActor()，不取请求体）。 */
    @Column(name = "resolved_by", length = 32)
    private String resolvedBy;

    /** 丢弃原因（DISCARDED 必填，截断 256 字）；重试/重放成功时记录处置说明可空。 */
    @Column(name = "resolve_note", length = 256)
    private String resolveNote;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = "PENDING";
        if (eventType == null) eventType = "UPSERT";
    }
}
