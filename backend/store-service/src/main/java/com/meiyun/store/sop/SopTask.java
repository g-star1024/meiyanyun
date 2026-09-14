package com.meiyun.store.sop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "sop_task", indexes = {
        @Index(name = "idx_sop_task_store", columnList = "storeCode"),
        @Index(name = "idx_sop_task_template", columnList = "templateId")
})
@Getter
@Setter
@NoArgsConstructor
public class SopTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false, length = 40)
    private String templateCode;

    @Column(nullable = false, length = 120)
    private String templateTitle;

    @Column(nullable = false, length = 20)
    private String category;

    @Column(nullable = false, length = 20)
    private String storeCode;

    @Column(nullable = false, length = 40)
    private String assignee;

    @Column(nullable = false, length = 10)
    private String priority = "MEDIUM";

    @Column(nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(columnDefinition = "TEXT")
    private String completedStepIds = "";

    @Column(columnDefinition = "TEXT")
    private String note;

    private LocalDate startedAt;

    private LocalDate completedAt;

    @Column(nullable = false, length = 40)
    private String createdBy;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
