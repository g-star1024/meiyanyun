package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 积分规则（B 端配置，单行）。 */
@Entity
@Table(name = "point_rule")
@Getter @Setter @NoArgsConstructor
public class PointRule {

    @Id
    @Column(name = "rule_id")
    private Integer ruleId;

    @Column(name = "earn_rate", nullable = false, precision = 6, scale = 2)
    private BigDecimal earnRate;

    @Column(name = "redeem_ratio", nullable = false, precision = 6, scale = 2)
    private BigDecimal redeemRatio;

    @Column(name = "expire_months", nullable = false)
    private Integer expireMonths;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
