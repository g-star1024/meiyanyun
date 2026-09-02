package com.meiyun.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 审计日志（DDL §10，T1-04 append-only）。
 * <p>应用账号在数据库中仅 GRANT INSERT + SELECT，无 UPDATE/DELETE。
 * 本实体对应不可篡改的 SHA-256 链式审计：每条 prev_hash 指向上一条 cur_hash。</p>
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "biz_type", nullable = false, length = 16)
    private String bizType;

    @Column(name = "txn_no", length = 24)
    private String txnNo;

    @Column(name = "actor", nullable = false, length = 32)
    private String actor;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    /** 业务载荷，JSON 字符串（落库为 jsonb）。 */
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    @Column(name = "cur_hash", nullable = false, length = 64)
    private String curHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);

    public AuditLog(String bizType, String txnNo, String actor, String action, String payload,
                   String prevHash, String curHash) {
        this.bizType = bizType;
        this.txnNo = txnNo;
        this.actor = actor;
        this.action = action;
        this.payload = payload;
        this.prevHash = prevHash;
        this.curHash = curHash;
    }
}
