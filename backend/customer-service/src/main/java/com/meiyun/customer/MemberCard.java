package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 会员卡项（预付费卡余额）。与退卡链路 txn_card_cancel 衔接。
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

    @Column(nullable = false, length = 8)
    private String status;                   // 在用/退卡中/已退卡

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (balance == null) balance = 0L;
        if (status == null) status = "在用";
    }
}
