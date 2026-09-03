package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 优惠券核销流水（M5-12 扫码核销台）。
 *
 * <p>每次核销动作一条：OK 正常核销回写券 usedQty；异常（DUPLICATE 重复 / FORGED 伪造 /
 * EXPIRED 过期）同样落流水供审计与告警，不回写券。
 * 金额口径（铁律：bigint 存分）：orderAmountFen 订单金额、discountFen 优惠抵扣额。
 */
@Entity
@Table(name = "coupon_writeoff_record")
@Getter @Setter @NoArgsConstructor
public class CouponWriteoffRecord {

    @Id
    @Column(name = "writeoff_id", length = 24)
    private String writeoffId;

    /** 录入/扫描的券码（原样留存，含伪造码）。 */
    @Column(name = "coupon_code", nullable = false, length = 32)
    private String couponCode;

    /** 命中的券模板 ID（伪造码为空）。 */
    @Column(name = "coupon_id", length = 24)
    private String couponId;

    @Column(name = "coupon_name", nullable = false, length = 64)
    private String couponName;

    @Column(name = "customer_name", nullable = false, length = 32)
    private String customerName;

    @Column(name = "customer_phone", nullable = false, length = 20)
    private String customerPhone;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "store_name", nullable = false, length = 64)
    private String storeName;

    /** 核销订单金额（分）。 */
    @Column(name = "order_amount_fen", nullable = false)
    private Long orderAmountFen;

    /** 优惠抵扣额（分）。 */
    @Column(name = "discount_fen", nullable = false)
    private Long discountFen;

    /** 核销渠道：门店核销。 */
    @Column(nullable = false, length = 16)
    private String channel;

    /** OK 正常 / DUPLICATE 重复核销 / FORGED 伪造券码 / EXPIRED 已过期。 */
    @Column(nullable = false, length = 10)
    private String status;

    /** 拦截/提示原因（异常状态为拦截理由；OK 且未达门槛为提示语，可空）。 */
    @Column(length = 200)
    private String reason;

    @Column(nullable = false, length = 32)
    private String operator;

    @Column(name = "verified_at", nullable = false)
    private OffsetDateTime verifiedAt;
}
