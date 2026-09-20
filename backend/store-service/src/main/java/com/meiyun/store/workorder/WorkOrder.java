package com.meiyun.store.workorder;

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
@Table(name = "work_order",
        uniqueConstraints = @UniqueConstraint(name = "uk_wo_no", columnNames = {"wo_no"}),
        indexes = {
                @Index(name = "idx_wo_store", columnList = "store_code"),
                @Index(name = "idx_wo_status", columnList = "status"),
                @Index(name = "idx_wo_assignee", columnList = "assignee")
        })
@Getter
@Setter
@NoArgsConstructor
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wo_no", nullable = false, length = 32)
    private String woNo;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    @Column(name = "description", nullable = false, length = 512)
    private String description;

    @Column(name = "customer_name", length = 64)
    private String customerName;

    @Column(name = "project", length = 64)
    private String project;

    @Column(name = "room", length = 64)
    private String room;

    @Column(name = "assignee", nullable = false, length = 64)
    private String assignee;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "priority", nullable = false, length = 16)
    private String priority;

    @Column(name = "deadline", nullable = false)
    private OffsetDateTime deadline;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
