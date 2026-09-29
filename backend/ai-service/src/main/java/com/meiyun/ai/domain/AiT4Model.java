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
@Table(name = "ai_t4_model")
@Getter
@Setter
@NoArgsConstructor
public class AiT4Model {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "model_id")
    private Long modelId;

    /** 业务码（mdl-*，前端主键锚；卡4 监控跨表引用） */
    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    /** CLASSIFICATION / REGRESSION / NLP / CV / RECOMMEND / GENERATIVE */
    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "description", nullable = false)
    private String description = "";

    @Column(name = "owner", nullable = false)
    private String owner;

    @Column(name = "department", nullable = false)
    private String department;

    /** 逗号分隔标签串（前端 string[] 适配层互转） */
    @Column(name = "tags")
    private String tags;

    /** DRAFT / TRAINING / READY / PUBLISHED / DEPRECATED */
    @Column(name = "status", nullable = false)
    private String status = "DRAFT";

    @Column(name = "current_version")
    private String currentVersion;

    @Column(name = "input_schema", nullable = false)
    private String inputSchema = "{}";

    @Column(name = "output_schema", nullable = false)
    private String outputSchema = "{}";

    @Column(name = "call_count_30d", nullable = false)
    private Long callCount30d = 0L;

    @Column(name = "avg_latency_ms", nullable = false)
    private Integer avgLatencyMs = 0;

    /** 近30天错误率（%） */
    @Column(name = "error_rate", nullable = false)
    private Double errorRate = 0.0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
