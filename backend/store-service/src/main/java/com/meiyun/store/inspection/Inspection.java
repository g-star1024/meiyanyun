package com.meiyun.store.inspection;

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
    name = "inspection",
    uniqueConstraints = @UniqueConstraint(name = "uk_ins_no", columnNames = "ins_no"),
    indexes = {
        @Index(name = "idx_ins_store", columnList = "store_code"),
        @Index(name = "idx_ins_status", columnList = "status"),
        @Index(name = "idx_ins_type", columnList = "type"),
    }
)
public class Inspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ins_no", nullable = false, length = 20)
    private String insNo;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "inspected_at", nullable = false)
    private OffsetDateTime inspectedAt;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "total_score", nullable = false)
    private Integer totalScore = 0;

    @Column(name = "issue_count", nullable = false)
    private Integer issueCount = 0;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "inspector", nullable = false, length = 32)
    private String inspector;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    void fillDefaults() {
        if (totalScore == null) totalScore = 0;
        if (issueCount == null) issueCount = 0;
        if (status == null) status = "PENDING";
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
