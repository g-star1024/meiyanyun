package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 积分流水台账（append-only：只 INSERT，与审计链同哲学）。
 */
@Entity
@Table(name = "points_ledger")
@Getter @Setter @NoArgsConstructor
public class PointsLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ledger_id")
    private Long ledgerId;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "change_amt", nullable = false)
    private Long changeAmt;                   // 正=获得，负=兑换/扣减

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    @Column(nullable = false, length = 64)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
