package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 兑换记录（C 端下单 → B 端审核队列，双签审批）。 */
@Entity
@Table(name = "mall_exchange")
@Getter @Setter @NoArgsConstructor
public class MallExchange {

    @Id
    @Column(name = "exchange_id", length = 24)
    private String exchangeId;

    @Column(name = "product_id", nullable = false, length = 24)
    private String productId;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "points_spent", nullable = false)
    private Integer pointsSpent;

    @Column(nullable = false)
    private Integer qty;

    @Column(nullable = false, length = 8)
    private String status;        // 待审核 | 已通过 | 已拒绝 | 已发放

    @Column(length = 32)
    private String sign1;
    @Column(name = "sign1_role", length = 32)
    private String sign1Role;
    @Column(name = "signed_at1")
    private OffsetDateTime signedAt1;

    @Column(length = 32)
    private String sign2;
    @Column(name = "sign2_role", length = 32)
    private String sign2Role;
    @Column(name = "signed_at2")
    private OffsetDateTime signedAt2;

    @Column(name = "reject_reason", length = 128)
    private String rejectReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
