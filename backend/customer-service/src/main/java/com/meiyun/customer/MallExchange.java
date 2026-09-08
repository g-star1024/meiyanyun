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

    /** 收货人（实物商品兑换时会员填写，审核队列展示）。 */
    @Column(name = "ship_name", length = 32)
    private String shipName;

    /** 收货电话（脱敏存储）。 */
    @Column(name = "ship_phone", length = 32)
    private String shipPhone;

    /** 收货地址。 */
    @Column(name = "ship_address", length = 128)
    private String shipAddress;

    /** 下单幂等键（客户端生成，同键重放返回既有单，不重复落库/审计）。 */
    @Column(name = "client_token", length = 64)
    private String clientToken;

    /** 履约发放时间（已通过 → 已发放）。 */
    @Column(name = "fulfilled_at")
    private OffsetDateTime fulfilledAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // ==================== 读模型冗余（不落库，端点解析注入；零技术码外露铁律） ====================

    /** 客户姓名（customer_id → customer.name，同库解析）。 */
    @Transient
    private String customerName;

    /** 商品名称（product_id → mall_product.product_name，同库解析）。 */
    @Transient
    private String productName;
}
