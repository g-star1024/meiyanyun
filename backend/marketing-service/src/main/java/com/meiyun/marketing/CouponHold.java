package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * C 端会员持券（域⑤ 券条线）。小程序自助领取落账，以非空 idem_key 唯一保证领取幂等
 * （idem_key = couponId + ":" + customerId，同券同人重复领取重放直返既有行）。
 *
 * <p>status ∈ HELD（持有中）/ USED（已核销）/ EXPIRED（已过期）/ REVOKED（已作废）。
 * 库存扣减口径：领取成功即 coupon_template.issued_qty + 1（与 B 端发放同口径），
 * 防超发由 CouponService 实例 synchronized 锁串行化（C 端领取与 B 端发放同锁）。
 */
@Entity
@Table(name = "coupon_hold",
        uniqueConstraints = @UniqueConstraint(name = "uk_coupon_hold_idem",
                columnNames = {"idem_key"}))
@Getter
@Setter
@NoArgsConstructor
public class CouponHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_id", nullable = false, length = 24)
    private String couponId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    /** 幂等键：couponId + ":" + customerId（同券同人至多持有一张）。非空唯一。 */
    @Column(name = "idem_key", nullable = false, length = 128)
    private String idemKey;

    @Column(nullable = false, length = 8)
    private String status; // HELD / USED / EXPIRED / REVOKED

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
