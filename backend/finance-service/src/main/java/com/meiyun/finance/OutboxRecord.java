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

    /** F2 #21 哨兵：最近一次扫描/挂差异原因（中文），人工对账端点不写此列。 */
    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /**
     * F2 #21 哨兵：已重试次数。Java 侧初始化 0，保证 postOne() new 后未显式 set
     * 时 insert 不为 null（DB 列 NOT NULL DEFAULT 0，但 JPA 显式插列会绕过 DB 默认值）。
     */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;
}
