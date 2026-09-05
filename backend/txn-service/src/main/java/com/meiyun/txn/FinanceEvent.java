package com.meiyun.txn;

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
 * 资金事件本地 outbox（B3 合规写，业务表 ddl-auto）。
 *
 * <p>outbox 模式：收款收齐 / 退款退卡终审 / 卡扣划扣完成时，业务事务内同写本事件表（PENDING）；
 * {@link FinanceEventRetryJob} 定时至少一次投递 finance {@code POST /api/finance/internal/entries}，
 * finance 侧 idem_key 幂等去重。业务动作不依赖 finance 可用——投递失败仅重试/告警，不回滚业务。
 *
 * <p>状态机：PENDING（待投递）→ SENT（finance 已受理）；DEAD（finance 4xx 确定性拒绝，
 * 如校验不通过，不再重试，留待财务对账人工发现并处理）。
 */
@Entity
@Table(name = "finance_event")
@Getter
@Setter
@NoArgsConstructor
public class FinanceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    /** 事件类型：ORDER_PAID / REFUND_CONFIRMED / CARD_CANCEL_CONFIRMED / WRITEOFF_DONE。 */
    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    /** 业务单据号（订单号 / 退款退卡单号 / 划扣单号），审计与排查锚点。 */
    @Column(name = "biz_ref", nullable = false, length = 48)
    private String bizRef;

    /** 投递负载：FundEntryCmd 列表 JSON（与 finance 落账契约逐字段对齐）。 */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    /** PENDING / SENT / DEAD。 */
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

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = "PENDING";
    }
}
