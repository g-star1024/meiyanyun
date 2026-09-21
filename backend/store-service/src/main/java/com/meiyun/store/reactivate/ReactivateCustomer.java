package com.meiyun.store.reactivate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "reactivate",
    uniqueConstraints = @UniqueConstraint(name = "uk_rc_no", columnNames = {"rc_no", "store_code"}),
    indexes = {
        @Index(name = "idx_rc_store", columnList = "store_code"),
        @Index(name = "idx_rc_status", columnList = "status"),
        @Index(name = "idx_rc_tier", columnList = "tier"),
    }
)
public class ReactivateCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rc_no", nullable = false, length = 20)
    private String rcNo;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "level", nullable = false, length = 16)
    private String level;

    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "last_visit_days", nullable = false)
    private Integer lastVisitDays = 0;

    @Column(name = "card_balance_fen", nullable = false)
    private Long cardBalanceFen = 0L;

    @Column(name = "tier", nullable = false, length = 8)
    private String tier = "T30";

    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "assignee", length = 64)
    private String assignee;

    @Column(name = "channel", length = 8)
    private String channel;

    @Column(name = "next_follow_at")
    private OffsetDateTime nextFollowAt;

    @PrePersist
    void fillDefaults() {
        if (lastVisitDays == null) lastVisitDays = 0;
        if (cardBalanceFen == null) cardBalanceFen = 0L;
        if (tier == null) tier = "T30";
        if (status == null) status = "PENDING";
    }
}
