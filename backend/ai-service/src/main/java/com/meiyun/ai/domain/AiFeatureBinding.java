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
@Table(name = "ai_feature_binding")
@Getter
@Setter
@NoArgsConstructor
public class AiFeatureBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "binding_id")
    private Long bindingId;

    @Column(name = "feature_code", nullable = false, unique = true)
    private String featureCode;

    @Column(name = "feature_name", nullable = false)
    private String featureName;

    @Column(name = "model_id")
    private Long modelId;

    /** ALL / SPECIFIED */
    @Column(name = "store_scope", nullable = false)
    private String storeScope = "ALL";

    @Column(name = "store_codes")
    private String storeCodes;

    @Column(name = "prompt_template")
    private String promptTemplate;

    @Column(name = "param_overrides")
    private String paramOverrides;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    @Column(name = "require_approval", nullable = false)
    private Boolean requireApproval = false;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
