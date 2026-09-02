package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "customer")
@Getter @Setter @NoArgsConstructor
public class Customer {

    @Id
    @Column(name = "customer_id", length = 16)
    private String customerId;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(nullable = false, length = 16)
    private String phone;

    @Column(nullable = false, length = 4)
    private String gender;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(nullable = false, length = 8)
    private String level;                     // 会员等级（中文，全站统一契约）：普通/银卡/金卡/钻石/黑卡

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "channel", length = 16)
    private String channel;                   // 来源：WALK_IN/REFERRAL/ONLINE_APPT/MARKETING

    @Column(name = "total_spend", precision = 12, scale = 2)
    private java.math.BigDecimal totalSpend;  // 累计消费（元）

    @Column(name = "visit_count")
    private Integer visitCount;               // 到店次数

    @Column(name = "owner_staff_id", length = 16)
    private String ownerStaffId;              // 归属人（空=公海）

    @Column(nullable = false)
    private Long points;

    @Column(nullable = false, length = 8)
    private String status;                    // 活跃/沉睡/流失

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (points == null) points = 0L;
        if (status == null) status = "活跃";
        if (totalSpend == null) totalSpend = java.math.BigDecimal.ZERO;
        if (visitCount == null) visitCount = 0;
    }
}
