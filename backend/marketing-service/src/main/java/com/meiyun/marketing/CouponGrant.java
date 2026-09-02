package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 优惠券发放记录（防超发台账）。
 * 一次发放动作一条：GRANTED 成功（含部分发放）；库存为 0 时不产生记录，由接口直接 409 拦截。
 */
@Entity
@Table(name = "coupon_grant")
@Getter @Setter @NoArgsConstructor
public class CouponGrant {

    @Id
    @Column(name = "grant_id", length = 24)
    private String grantId;

    @Column(name = "coupon_id", nullable = false, length = 24)
    private String couponId;

    @Column(name = "coupon_name", nullable = false, length = 64)
    private String couponName;

    /** 发放范围：ALL / NEW / SEGMENT / DESIGNATED。 */
    @Column(name = "grant_scope", nullable = false, length = 10)
    private String grantScope;

    /** 发放对象名称（如「本月新客」「高价值客户(42人)」）。 */
    @Column(name = "target_name", nullable = false, length = 64)
    private String targetName;

    /** 本次实际发放张数。 */
    @Column(nullable = false)
    private Integer grantCount;

    /** GRANTED 成功 / FAILED 失败。 */
    @Column(nullable = false, length = 8)
    private String status;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    @Column(nullable = false, length = 32)
    private String operator;
}
