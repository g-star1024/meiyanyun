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
@Table(name = "ai_invoke_log")
@Getter
@Setter
@NoArgsConstructor
public class AiInvokeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "invoked_at", insertable = false, updatable = false)
    private OffsetDateTime invokedAt;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "store_code")
    private String storeCode;

    @Column(name = "feature_code")
    private String featureCode;

    @Column(name = "provider_code")
    private String providerCode;

    @Column(name = "model_code")
    private String modelCode;

    @Column(name = "prompt_snippet")
    private String promptSnippet;

    @Column(name = "output_snippet")
    private String outputSnippet;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "success", nullable = false)
    private Boolean success = true;

    @Column(name = "error_code", length = 512)
    private String errorCode;

    /** 费用，单位：分 */
    @Column(name = "cost_fen", nullable = false)
    private Long costFen = 0L;
}
