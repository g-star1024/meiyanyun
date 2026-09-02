package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 会员卡项（txn-service 侧映射，供划扣账实校验同事务扣减；与 customer-service 共库共表）。
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

    @Column(nullable = false, length = 8)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
