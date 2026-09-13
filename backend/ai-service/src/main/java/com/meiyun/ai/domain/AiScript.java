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
@Table(name = "ai_script")
@Getter
@Setter
@NoArgsConstructor
public class AiScript {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "script_id")
    private Long scriptId;

    /** icebreak / upsell / objection */
    @Column(name = "scene", nullable = false)
    private String scene;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "content", nullable = false)
    private String content;

    /** MANUAL / AI */
    @Column(name = "source", nullable = false)
    private String source = "MANUAL";

    @Column(name = "invoke_log_id")
    private Long invokeLogId;

    @Column(name = "model_code")
    private String modelCode;

    @Column(name = "rating", nullable = false)
    private Integer rating = 5;

    @Column(name = "adopted_count", nullable = false)
    private Long adoptedCount = 0L;

    @Column(name = "feedback_count", nullable = false)
    private Long feedbackCount = 0L;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "store_code")
    private String storeCode;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
