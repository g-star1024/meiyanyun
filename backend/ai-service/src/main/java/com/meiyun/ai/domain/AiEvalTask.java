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

import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_eval_task")
@Getter
@Setter
@NoArgsConstructor
public class AiEvalTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "task_name", nullable = false)
    private String taskName;

    /** FEATURE / MODEL */
    @Column(name = "eval_scope", nullable = false)
    private String evalScope;

    @Column(name = "target_code", nullable = false)
    private String targetCode;

    @Column(name = "target_name", nullable = false)
    private String targetName;

    @Column(name = "window_days", nullable = false)
    private Integer windowDays = 7;

    @Column(name = "period_start", nullable = false)
    private OffsetDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private OffsetDateTime periodEnd;

    @Column(name = "metrics_json", nullable = false)
    private String metricsJson = "{}";

    /** RUNNING / DONE */
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
