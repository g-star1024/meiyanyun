package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 优惠券模板。 */
@Entity
@Table(name = "coupon_template")
@Getter @Setter @NoArgsConstructor
public class CouponTemplate {

    @Id
    @Column(name = "coupon_id", length = 24)
    private String couponId;

    @Column(name = "coupon_name", nullable = false, length = 64)
    private String couponName;

    @Column(name = "face_value", nullable = false)
    private Long faceValue;

    @Column(nullable = false)
    private Long threshold;

    @Column(name = "total_qty", nullable = false)
    private Integer totalQty;

    @Column(name = "issued_qty", nullable = false)
    private Integer issuedQty;

    @Column(name = "used_qty", nullable = false)
    private Integer usedQty;

    @Column(nullable = false, length = 8)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
