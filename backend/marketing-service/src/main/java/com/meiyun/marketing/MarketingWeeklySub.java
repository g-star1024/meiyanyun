package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 营销周报订阅（P5-B94 D6）：一人一行（staff_no UK），订阅主体＝登录员工本人（JWT 取），
 * 无门店维度（区域经理亦可订阅）。last_sent_week 记录最近成功推送的 ISO 周戳（如 2026-W39），
 * 与 txn 侧 Notification idemKey 构成 Job 双防重（D12）。JPA ddl-auto=update 派生，零手写 DDL。
 */
@Entity
@Table(name = "marketing_weekly_sub", uniqueConstraints =
        @UniqueConstraint(name = "uk_mkt_weekly_sub_staff", columnNames = "staff_no"))
@Getter
@Setter
@NoArgsConstructor
public class MarketingWeeklySub {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订阅员工工号（登录人本人）。 */
    @Column(name = "staff_no", nullable = false, length = 32)
    private String staffNo;

    /** 订阅开关。 */
    @Column(nullable = false)
    private boolean enabled;

    /** 最近成功推送周戳（ISO 周，如 2026-W39），可空。 */
    @Column(name = "last_sent_week", length = 8)
    private String lastSentWeek;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
