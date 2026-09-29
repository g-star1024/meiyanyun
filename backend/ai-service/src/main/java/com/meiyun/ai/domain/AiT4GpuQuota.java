package com.meiyun.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_t4_gpu_quota")
@Getter
@Setter
@NoArgsConstructor
public class AiT4GpuQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "quota_id")
    private Long quotaId;

    /** 业务码（quota-*，前端主键锚） */
    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "department", nullable = false)
    private String department;

    @Column(name = "project", nullable = false)
    private String project;

    @Column(name = "gpu_hours", nullable = false)
    private Integer gpuHours = 0;

    @Column(name = "gpu_hours_used", nullable = false)
    private Integer gpuHoursUsed = 0;

    @Column(name = "budget", nullable = false)
    private BigDecimal budget = BigDecimal.ZERO;

    @Column(name = "spent", nullable = false)
    private BigDecimal spent = BigDecimal.ZERO;

    /** 统计周期（如 2026-08 / 2026-Q3） */
    @Column(name = "period", nullable = false)
    private String period;

    /** ACTIVE / EXCEEDED / EXPIRED */
    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
