package com.meiyun.store.requisition;

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
@Table(name = "requisition",
        uniqueConstraints = @UniqueConstraint(name = "uk_rq_no", columnNames = {"rq_no"}),
        indexes = {
                @Index(name = "idx_rq_store", columnList = "store_code"),
                @Index(name = "idx_rq_status", columnList = "status"),
                @Index(name = "idx_rq_applicant", columnList = "applicant")
        })
@Getter
@Setter
@NoArgsConstructor
public class Requisition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rq_no", nullable = false, length = 32)
    private String rqNo;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "applicant", nullable = false, length = 64)
    private String applicant;

    @Column(name = "purpose", length = 255)
    private String purpose;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "approver", length = 64)
    private String approver;

    @Column(name = "receiver", length = 64)
    private String receiver;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "source_type", length = 16)
    private String sourceType;

    @Column(name = "source_ref", length = 32)
    private String sourceRef;

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
