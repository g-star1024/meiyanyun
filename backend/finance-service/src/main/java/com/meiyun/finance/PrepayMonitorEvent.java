package com.meiyun.finance;

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
 * 预收合规监控事件（B63 卡3 L85，V38 prepay_monitor_event）。
 *
 * <p>扫描命中落 OPEN 事件（同规则同店部分唯一索引保证仅一条 OPEN），仅新发 OPEN 事件触发
 * txn compliance-alert；下一轮不再命中回转 RESOLVED，之后可再次开新事件（轮次计入 idem_key）。
 */
@Entity
@Table(name = "prepay_monitor_event")
@Getter
@Setter
@NoArgsConstructor
public class PrepayMonitorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    /** PM:{ruleCode}:{storeCode}:R{轮次}。 */
    @Column(name = "idem_key", length = 96, nullable = false)
    private String idemKey;

    @Column(name = "rule_code", length = 16, nullable = false)
    private String ruleCode;

    @Column(name = "store_code", length = 16, nullable = false)
    private String storeCode;

    @Column(name = "type", length = 16, nullable = false)
    private String type;

    @Column(name = "level", length = 8, nullable = false)
    private String level;

    @Column(name = "title", length = 128, nullable = false)
    private String title;

    @Column(name = "content", length = 500, nullable = false)
    private String content;

    @Column(name = "amount_fen", nullable = false)
    private long amountFen;

    /** PM{yyMMdd}:{ruleCode}:{storeCode}，≤32 供 COMPLIANCE: 幂等键使用。 */
    @Column(name = "biz_ref", length = 32, nullable = false)
    private String bizRef;

    /** OPEN / RESOLVED。 */
    @Column(name = "status", length = 8, nullable = false)
    private String status = "OPEN";

    @Column(name = "fired_at", nullable = false)
    private OffsetDateTime firedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (firedAt == null) firedAt = OffsetDateTime.now();
    }
}
