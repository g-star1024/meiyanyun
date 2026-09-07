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
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_id_seq")
    @SequenceGenerator(name = "outbox_id_seq", sequenceName = "outbox_record_outbox_id_seq", allocationSize = 1)
    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(name = "biz_type", length = 16)
    private String bizType;

    // V8：24 → 64。B11 成本结转 bizRef 为复合格式 ruleId:yyyy-MM:storeCode（种子 26 字符、
    // 用户自建规则最长约 40 字符），落 outbox 时 txnNo=bizRef，原 24 位装不下会整笔回滚。
    @Column(name = "txn_no", length = 64)
    private String txnNo;

    @Column(nullable = false)
    private Long amount;

    @Column(length = 16)
    private String channel;

    @Column(length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "reconciled_at")
    private OffsetDateTime reconciledAt;
}
