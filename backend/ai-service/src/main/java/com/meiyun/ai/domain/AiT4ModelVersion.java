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
@Table(name = "ai_t4_model_version")
@Getter
@Setter
@NoArgsConstructor
public class AiT4ModelVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "version_id")
    private Long versionId;

    @Column(name = "model_id", nullable = false)
    private Long modelId;

    @Column(name = "version", nullable = false)
    private String version;

    /** 训练指标 JSON 文本（如 {"auc":0.89}，与前端 Record<string,number> 对应） */
    @Column(name = "metrics", nullable = false)
    private String metrics = "{}";

    /** DRAFT / TRAINING / READY / PUBLISHED / DEPRECATED */
    @Column(name = "status", nullable = false)
    private String status = "TRAINING";

    @Column(name = "trained_at", insertable = false, updatable = false)
    private OffsetDateTime trainedAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** 发布审批人（审批中心 decide 联动写入；回滚写操作人） */
    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "remark")
    private String remark;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
