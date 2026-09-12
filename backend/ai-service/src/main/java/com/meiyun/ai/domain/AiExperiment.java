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
@Table(name = "ai_experiment")
@Getter
@Setter
@NoArgsConstructor
public class AiExperiment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "experiment_id")
    private Long experimentId;

    @Column(name = "experiment_name", nullable = false)
    private String experimentName;

    @Column(name = "control_model", nullable = false)
    private String controlModel;

    @Column(name = "experiment_model", nullable = false)
    private String experimentModel;

    @Column(name = "window_days", nullable = false)
    private Integer windowDays = 7;

    @Column(name = "period_start", nullable = false)
    private OffsetDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private OffsetDateTime periodEnd;

    @Column(name = "control_metrics", nullable = false)
    private String controlMetrics = "{}";

    @Column(name = "experiment_metrics", nullable = false)
    private String experimentMetrics = "{}";

    /** 实验组成功率 − 对照组成功率（百分点） */
    @Column(name = "lift_pp")
    private BigDecimal liftPp;

    /** RUNNING / FINISHED */
    @Column(name = "status", nullable = false)
    private String status = "RUNNING";

    @Column(name = "conclusion")
    private String conclusion;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
