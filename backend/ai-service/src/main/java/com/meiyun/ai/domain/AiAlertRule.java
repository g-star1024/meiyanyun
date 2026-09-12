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
@Table(name = "ai_alert_rule")
@Getter
@Setter
@NoArgsConstructor
public class AiAlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "rule_code", nullable = false, unique = true)
    private String ruleCode;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    /** LATENCY_P99 / ERROR_RATE / CALL_COUNT / SUCCESS_RATE / QUOTA_WATERMARK */
    @Column(name = "metric", nullable = false)
    private String metric;

    /** > / < */
    @Column(name = "compare_op", nullable = false)
    private String compareOp = ">";

    @Column(name = "threshold_num", nullable = false)
    private BigDecimal thresholdNum;

    @Column(name = "window_minutes", nullable = false)
    private Integer windowMinutes = 1440;

    @Column(name = "notify_channel", nullable = false)
    private String notifyChannel = "站内信";

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
