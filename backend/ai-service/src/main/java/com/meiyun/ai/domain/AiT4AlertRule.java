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
@Table(name = "ai_t4_alert_rule")
@Getter
@Setter
@NoArgsConstructor
public class AiT4AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;

    /** 业务码（rule-*，前端主键锚 id） */
    @Column(name = "code", nullable = false)
    private String code;

    /** 规则名（全局唯一） */
    @Column(name = "name", nullable = false)
    private String name;

    /** 监控对象模型 code（引用卡1 ai_t4_model.code，禁改名） */
    @Column(name = "model_code", nullable = false)
    private String modelCode;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** DRIFT / LATENCY / ERROR_RATE / ACCURACY / QPS_DROP */
    @Column(name = "metric", nullable = false)
    private String metric;

    @Column(name = "threshold", nullable = false)
    private java.math.BigDecimal threshold;

    /** > / < / >= / <= */
    @Column(name = "operator", nullable = false)
    private String operator;

    /** CRITICAL / WARNING / INFO */
    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 通知渠道 JSONB 数组（企微/邮件/短信）；Java 侧以 JSON 字符串持有 */
    @Column(name = "notify_channels", nullable = false, columnDefinition = "jsonb")
    private String notifyChannels = "[]";

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
