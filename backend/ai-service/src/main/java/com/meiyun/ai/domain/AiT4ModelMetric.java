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
@Table(name = "ai_t4_model_metric")
@Getter
@Setter
@NoArgsConstructor
public class AiT4ModelMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "metric_id")
    private Long metricId;

    /** 模型 code（引用卡1 ai_t4_model.code，禁改名；前端 modelId） */
    @Column(name = "model_code", nullable = false)
    private String modelCode;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** 每秒请求数（快照） */
    @Column(name = "qps", nullable = false)
    private Integer qps = 0;

    /** P99 延迟 ms（快照） */
    @Column(name = "latency_p99", nullable = false)
    private Integer latencyP99 = 0;

    /** 错误率 %（快照） */
    @Column(name = "error_rate", nullable = false)
    private BigDecimal errorRate = BigDecimal.ZERO;

    /** 漂移分数 0-1（快照） */
    @Column(name = "drift_score", nullable = false)
    private BigDecimal driftScore = BigDecimal.ZERO;

    /** 准确率 0-1（快照） */
    @Column(name = "accuracy", nullable = false)
    private BigDecimal accuracy = BigDecimal.ZERO;

    /** 快照采集时刻（前端 timestamp） */
    @Column(name = "snapshot_at", nullable = false)
    private OffsetDateTime snapshotAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
