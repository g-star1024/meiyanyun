package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 客户赠金账本（域⑤ 赠金额度发放与余额）。以非空 idem_key 唯一保证发赠金幂等
 * （旧 (source_biz_ref, rule_id) 复合约束在 rule_id 为 NULL 时被 PG 判 NULL DISTINCT 而失效，弃用）。
 *
 * <p>status ∈ VALID（可用）/ USED（余额扣尽）/ EXPIRED（过期清零）/ REVOKED（作废）。
 * 金额单位「分」，余额 balance_fen ≤ amount_fen。收银台抵扣按到期时间 FIFO 递减 balance_fen，
 * 扣尽方置 USED，逐笔明细落 grant_deduction 流水。
 */
@Entity
@Table(name = "customer_grant",
        uniqueConstraints = @UniqueConstraint(name = "uk_cg_idem",
                columnNames = {"idem_key"}))
@Getter
@Setter
@NoArgsConstructor
public class CustomerGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "rule_id", length = 24)
    private String ruleId;

    @Column(name = "amount_fen", nullable = false)
    private Long amountFen;

    @Column(name = "balance_fen", nullable = false)
    private Long balanceFen;

    @Column(name = "expire_at", nullable = false)
    private OffsetDateTime expireAt;

    @Column(nullable = false, length = 16)
    private String status; // VALID / USED / EXPIRED / REVOKED

    @Column(name = "source_biz_ref", length = 128)
    private String sourceBizRef;

    /** 幂等键：规则发放=RULE:{ruleId}:{sourceBizRef}；手工发放=客户端幂等号或 MANUAL:{customerId}:{uuid}。非空唯一。 */
    @Column(name = "idem_key", nullable = false, length = 128)
    private String idemKey;

    /** 客户归属门店（由客户域 internal 投影解析；解析失败落集团空串，不阻断发放）。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
