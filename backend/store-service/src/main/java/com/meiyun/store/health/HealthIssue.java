package com.meiyun.store.health;

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

/**
 * 健康度整改任务（B49 卡10）：severity 三档 HIGH/MEDIUM/LOW，
 * 状态机 OPEN → PROCESSING → RESOLVED，OPEN/PROCESSING → IGNORED。
 */
@Entity
@Table(name = "health_issue", indexes = {
        @Index(name = "idx_health_issue_store", columnList = "storeCode")
})
@Getter
@Setter
@NoArgsConstructor
public class HealthIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String storeCode;

    @Column(nullable = false, length = 16)
    private String dimension;

    @Column(nullable = false, length = 10)
    private String severity;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 500)
    private String detail;

    @Column(nullable = false, length = 16)
    private String status = "OPEN";

    @Column(length = 40)
    private String assignee;

    private LocalDate dueAt;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    private LocalDate resolvedAt;

    @Column(length = 500)
    private String resolution;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
