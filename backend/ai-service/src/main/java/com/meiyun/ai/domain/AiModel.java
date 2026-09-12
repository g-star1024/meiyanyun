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
@Table(name = "ai_model")
@Getter
@Setter
@NoArgsConstructor
public class AiModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "model_code", nullable = false)
    private String modelCode;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** 能力集合，逗号分隔：CHAT/EMBEDDING/VISION */
    @Column(name = "capabilities", nullable = false)
    private String capabilities = "CHAT";

    @Column(name = "context_window")
    private Integer contextWindow;

    @Column(name = "temperature")
    private BigDecimal temperature;

    @Column(name = "top_p")
    private BigDecimal topP;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "priority", nullable = false)
    private Integer priority = 100;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    /** SUCCESS / FAIL / UNKNOWN */
    @Column(name = "conn_status", nullable = false)
    private String connStatus = "UNKNOWN";

    @Column(name = "conn_message")
    private String connMessage;

    @Column(name = "conn_checked_at")
    private OffsetDateTime connCheckedAt;

    /** 元/百万 token，可空 */
    @Column(name = "input_price")
    private BigDecimal inputPrice;

    @Column(name = "output_price")
    private BigDecimal outputPrice;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
