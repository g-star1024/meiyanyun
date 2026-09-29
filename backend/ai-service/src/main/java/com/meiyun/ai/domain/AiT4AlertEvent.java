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
@Table(name = "ai_t4_alert_event")
@Getter
@Setter
@NoArgsConstructor
public class AiT4AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    /** 业务码（evt-*，前端主键锚 id） */
    @Column(name = "code", nullable = false)
    private String code;

    /** 触发规则 code（fk→ai_t4_alert_rule.code） */
    @Column(name = "rule_code", nullable = false)
    private String ruleCode;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "model_code", nullable = false)
    private String modelCode;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** CRITICAL / WARNING / INFO */
    @Column(name = "severity", nullable = false)
    private String severity;

    /** FIRING / ACKNOWLEDGED / RESOLVED */
    @Column(name = "status", nullable = false)
    private String status = "FIRING";

    @Column(name = "message", nullable = false)
    private String message = "";

    /** 触发时实际值 */
    @Column(name = "value", nullable = false)
    private BigDecimal value;

    /** 触发时阈值快照 */
    @Column(name = "threshold", nullable = false)
    private BigDecimal threshold;

    @Column(name = "triggered_at", nullable = false)
    private OffsetDateTime triggeredAt;

    @Column(name = "acknowledged_at")
    private OffsetDateTime acknowledgedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    /** 确认人（操作员显示名） */
    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
