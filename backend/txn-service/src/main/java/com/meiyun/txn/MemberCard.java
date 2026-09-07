package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 会员卡项（txn-service 侧映射，供划扣账实校验同事务扣减；与 customer-service 共库共表）。
 *
 * <p>B16 售卡开卡字段与 customer-service 权威实体保持同名同列同步（JPA ddl-auto 加列以
 * customer-service 为准，txn 只读/资产转移不写新字段）：product_code/card_type/expires_at/sale_no/gift_balance。
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
    private Long balance;

    /** 赠送金余额（分）：售卡活动赠送，customer 域权威维护，txn 只读投影。 */
    @Column(name = "gift_balance", nullable = false, columnDefinition = "bigint not null default 0")
    private Long giftBalance;

    @Column(nullable = false, length = 8)
    private String status;

    /** 售卡目录模板编码（CD-/CS-）；历史导入卡为空。 */
    @Column(name = "product_code", length = 32)
    private String productCode;

    /** 模板类型快照：CARD 卡项 / COURSE 疗程；历史卡为空。 */
    @Column(name = "card_type", length = 16)
    private String cardType;

    /** 有效期截止时间；历史卡为空。 */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    /** 售卡订单号（OD 单号，开卡幂等键）；历史导入卡为空。 */
    @Column(name = "sale_no", length = 24)
    private String saleNo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
