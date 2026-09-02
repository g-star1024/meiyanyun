package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** Outbox 对账事件队列（经营域→资金只读镜像）。只读核验，无资金动词。 */
@Entity
@Table(name = "outbox_record")
@Getter @Setter @NoArgsConstructor
public class OutboxRecord {

    @Id
    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(name = "biz_type", length = 16)
    private String bizType;

    @Column(name = "txn_no", length = 24)
    private String txnNo;

    @Column(nullable = false)
    private Long amount;

    @Column(length = 8)
    private String channel;

    @Column(length = 8)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "reconciled_at")
    private OffsetDateTime reconciledAt;
}
