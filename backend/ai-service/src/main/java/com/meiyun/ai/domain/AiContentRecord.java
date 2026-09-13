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
@Table(name = "ai_content_record")
@Getter
@Setter
@NoArgsConstructor
public class AiContentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "record_id")
    private Long recordId;

    /** wechat / poster / sms */
    @Column(name = "channel", nullable = false)
    private String channel;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "invoke_log_id")
    private Long invokeLogId;

    @Column(name = "model_code")
    private String modelCode;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    /** 费用，单位：分 */
    @Column(name = "cost_fen", nullable = false)
    private Long costFen = 0L;

    /** GENERATED / DEPLOYED */
    @Column(name = "status", nullable = false)
    private String status = "GENERATED";

    @Column(name = "deployed_at")
    private OffsetDateTime deployedAt;

    @Column(name = "deployed_by")
    private String deployedBy;

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
