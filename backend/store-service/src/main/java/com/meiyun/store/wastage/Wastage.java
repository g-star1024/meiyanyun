package com.meiyun.store.wastage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "wastage",
        uniqueConstraints = @UniqueConstraint(name = "uk_ws_no", columnNames = {"ws_no"}),
        indexes = {
                @Index(name = "idx_ws_store", columnList = "store_code"),
                @Index(name = "idx_ws_status", columnList = "status"),
                @Index(name = "idx_ws_reason", columnList = "reason")
        })
@Getter
@Setter
@NoArgsConstructor
public class Wastage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ws_no", nullable = false, length = 32)
    private String wsNo;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reason", nullable = false, length = 16)
    private String reason;

    @Column(name = "item_name", nullable = false, length = 64)
    private String itemName;

    @Column(name = "spec", length = 32)
    private String spec;

    @Column(name = "qty", nullable = false)
    private Integer qty;

    @Column(name = "unit", length = 8)
    private String unit;

    @Column(name = "amount_fen", nullable = false)
    private Long amountFen;

    @Column(name = "reporter", nullable = false, length = 64)
    private String reporter;

    @Column(name = "location", length = 64)
    private String location;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "approver", length = 64)
    private String approver;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
