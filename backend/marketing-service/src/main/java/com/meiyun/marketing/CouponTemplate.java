package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 优惠券模板。
 *
 * 金额口径（铁律：bigint 存分）：
 * - faceValue：AMOUNT 满减券 = 面值（分）；PACKAGE 券包 = 包子项金额合计（分）；
 *   RATE 折扣券 = 折扣×10 的整数（如 8.5 折存 85），无金额含义；
 * - threshold：使用门槛（分），0 表示无门槛；
 * - packageItems：券包子项 JSON 文本（[{"name":"满5000减600","value":60000}]，value 单位分）。
 */
@Entity
@Table(name = "coupon_template")
@Getter @Setter @NoArgsConstructor
public class CouponTemplate {

    @Id
    @Column(name = "coupon_id", length = 24)
    private String couponId;

    @Column(name = "coupon_name", nullable = false, length = 64)
    private String couponName;

    /** 券类型：AMOUNT 满减 / RATE 折扣 / PACKAGE 券包。 */
    @Column(name = "coupon_type", nullable = false, length = 8)
    private String couponType;

    /** 面值（分）；折扣券存折扣×10 整数（8.5 折 = 85）。 */
    @Column(name = "face_value", nullable = false)
    private Long faceValue;

    /** 使用门槛（分），0 = 无门槛。 */
    @Column(nullable = false)
    private Long threshold;

    @Column(name = "total_qty", nullable = false)
    private Integer totalQty;

    /** 已发放（领取）数量。 */
    @Column(name = "issued_qty", nullable = false)
    private Integer issuedQty;

    @Column(name = "used_qty", nullable = false)
    private Integer usedQty;

    /** 状态：DRAFT 草稿 / ACTIVE 进行中 / DISABLED 已停用（EXPIRED 已过期由有效期日期派生，不落库）。 */
    @Column(nullable = false, length = 8)
    private String status;

    /** 发放范围：ALL 全部 / NEW 新客 / SEGMENT 客群 / DESIGNATED 指定。 */
    @Column(name = "grant_scope", nullable = false, length = 10)
    private String grantScope;

    @Column(name = "grant_scope_name", length = 64)
    private String grantScopeName;

    /** 券包子项 JSON（仅 PACKAGE）。 */
    @Column(name = "package_items", length = 2000)
    private String packageItems;

    /** 关联活动 ID（可空）。 */
    @Column(name = "campaign_id", length = 24)
    private String campaignId;

    /** 展示券码（可空，活动关联券用）。 */
    @Column(name = "coupon_code", length = 32)
    private String couponCode;

    @Column(name = "valid_start")
    private LocalDate validStart;

    @Column(name = "valid_end")
    private LocalDate validEnd;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
