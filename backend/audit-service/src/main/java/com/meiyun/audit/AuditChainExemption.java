package com.meiyun.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 审计哈希链断链豁免登记（V83，append-only）。
 * <p>已知历史断链（如 prod 缺失 id 区段导致的结构性断链）经登记豁免后，
 * verifyChain 将其与「新增断链」分离呈现：ok 只由新增断链决定。
 * 本表只 INSERT 不 UPDATE/DELETE，audit_log 本体一行不动。</p>
 * <p>audit_log_id 唯一索引即幂等键：同一节点重复登记返回已存在记录。</p>
 */
@Entity
@Table(name = "audit_chain_exemption")
@Getter
@Setter
@NoArgsConstructor
public class AuditChainExemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被豁免的断链节点 audit_log.id（唯一，幂等键）。 */
    @Column(name = "audit_log_id", nullable = false, unique = true)
    private Long auditLogId;

    /** 登记时刻节点 prev_hash 快照（audit_log 不可变，恒等于现值）。 */
    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    /** 登记时刻节点 cur_hash 快照。 */
    @Column(name = "cur_hash", nullable = false, length = 64)
    private String curHash;

    /** 豁免理由（须引决策留档，如 04-backlog L43 拍板原文）。 */
    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    /** 登记人（系统通道=system，员工通道=JWT 真实人）。 */
    @Column(name = "registered_by", nullable = false, length = 32)
    private String registeredBy;

    @Column(name = "registered_at", nullable = false)
    private OffsetDateTime registeredAt = OffsetDateTime.now(ZoneOffset.UTC);

    public AuditChainExemption(Long auditLogId, String prevHash, String curHash,
                               String reason, String registeredBy) {
        this.auditLogId = auditLogId;
        this.prevHash = prevHash;
        this.curHash = curHash;
        this.reason = reason;
        this.registeredBy = registeredBy;
    }
}
