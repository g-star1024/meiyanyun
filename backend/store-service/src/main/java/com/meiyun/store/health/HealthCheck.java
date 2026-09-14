package com.meiyun.store.health;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 健康度巡检头（B49 卡10）：一店一行，store_code 直接作 PK（设计章 id+UNIQUE 微调，语义更自然）。
 */
@Entity
@Table(name = "health_check")
@Getter
@Setter
@NoArgsConstructor
public class HealthCheck {

    @Id
    @Column(name = "store_code", length = 20)
    private String storeCode;

    @Column(nullable = false)
    private LocalDate lastCheckedAt;

    @Column(nullable = false)
    private LocalDate nextCheckAt;

    @Column(nullable = false, length = 40)
    private String inspector;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
