package com.meiyun.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_global_cfg")
@Getter
@Setter
@NoArgsConstructor
public class AiGlobalCfg {

    @Id
    @Column(name = "cfg_id")
    private Integer cfgId = 1;

    @Column(name = "default_model_id")
    private Long defaultModelId;

    /** 灰度比例 0~100 */
    @Column(name = "gray_scale", nullable = false)
    private Integer grayScale = 100;

    @Column(name = "retention_months", nullable = false)
    private Integer retentionMonths = 12;

    @Column(name = "sensitive_check", nullable = false)
    private Boolean sensitiveCheck = true;

    @Column(name = "explainability", nullable = false)
    private Boolean explainability = true;

    @Column(name = "auto_audit", nullable = false)
    private Boolean autoAudit = true;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
