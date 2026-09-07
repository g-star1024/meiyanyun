package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 会员卡项（预付费卡余额）。与退卡链路 txn_card_cancel 衔接。
 *
 * <p>B16 售卡开卡补齐溯源/有效期/赠金字段（JPA ddl-auto 自动加列）：
 * product_code 售卡目录模板编码（CD-/CS-，历史导入卡为空）、card_type 模板类型快照
 * （CARD 卡项/COURSE 疗程，空为历史卡）、expires_at 有效期截止（售卡时按模板 validity_days 计算）、
 * sale_no 售卡订单号（OD 单号，开卡幂等键）、gift_balance 赠送金余额（分）。
 */
@Entity
@Table(name = "member_card")
@Getter @Setter @NoArgsConstructor
public class MemberCard {

    @Id
    @Column(name = "card_no", length = 24)
    private String cardNo;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "card_item", nullable = false, length = 64)
    private String cardItem;

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "total_times", nullable = false)
    private Integer totalTimes;

    @Column(name = "remain_times", nullable = false)
    private Integer remainTimes;

    @Column(nullable = false)
    private Long balance;                    // 剩余余额（分）

    /** 赠送金余额（分）：售卡充值活动赠送部分，消费优先扣赠（本批随开卡落库，扣赠在消费链路另行承接）。 */
    @Column(name = "gift_balance", nullable = false, columnDefinition = "bigint not null default 0")
    private Long giftBalance;

    @Column(nullable = false, length = 8)
    private String status;                   // 在用/退卡中/已退卡/已用完

    /** 售卡目录模板编码（catalog_product.product_code，CD- 卡项 / CS- 疗程）；历史导入卡为空。 */
    @Column(name = "product_code", length = 32)
    private String productCode;

    /** 模板类型快照：CARD 卡项 / COURSE 疗程；历史卡为空（由 total_times 推断）。 */
    @Column(name = "card_type", length = 16)
    private String cardType;

    /** 有效期截止时间（售卡时 = 开卡时间 + 模板 validity_days）；历史卡为空。 */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    /** 售卡订单号（txn_order.order_no，OD 单号）；售卡开卡幂等键，历史导入卡为空。 */
    @Column(name = "sale_no", length = 24)
    private String saleNo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (balance == null) balance = 0L;
        if (giftBalance == null) giftBalance = 0L;
        if (status == null) status = "在用";
    }
}
