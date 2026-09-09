package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 赠金规则（域⑤ 赠金高级规则）。满赠阶梯/活动赠送/新客礼等统一在此配置。
 *
 * <p>grant_type ∈ CONSUME_THRESHOLD（消费满赠）/ ACTIVITY（活动赠送）/ NEWCOMER（新客礼）。
 * threshold_fen 仅满赠类使用；applicable_stores 为空串表示全部门店；expire_months 为赠金有效期（月）。
 * 状态机：ENABLED / DISABLED（仅启用规则参与发赠金）。
 */
@Entity
@Table(name = "grant_rule")
@Getter
@Setter
@NoArgsConstructor
public class GrantRule {

    @Id
    @Column(name = "rule_id", length = 24)
    private String ruleId; // GRT + yyyyMMdd + '-' + 6 位序号（BizNoGenerator）

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "grant_type", nullable = false, length = 24)
    private String grantType;

    /** 消费满赠门槛（分），满赠类使用，其余可为空。 */
    @Column(name = "threshold_fen")
    private Long thresholdFen;

    /** 赠金额度（分）。 */
    @Column(name = "grant_amount_fen", nullable = false)
    private Long grantAmountFen;

    @Column(name = "expire_months", nullable = false)
    private int expireMonths;

    /** 适用门店码逗号串，空=全部门店。 */
    @Column(name = "applicable_stores", columnDefinition = "TEXT")
    private String applicableStores;

    @Column(nullable = false, length = 16)
    private String status; // ENABLED / DISABLED

    @Column(nullable = false)
    private int priority;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
