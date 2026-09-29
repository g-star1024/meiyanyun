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
@Table(name = "ai_t4_feature")
@Getter
@Setter
@NoArgsConstructor
public class AiT4Feature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "feature_id")
    private Long featureId;

    /** 业务码（feat-*，前端主键锚） */
    @Column(name = "code", nullable = false)
    private String code;

    /** 特征名（全局唯一） */
    @Column(name = "name", nullable = false)
    private String name;

    /** 特征分组（前端 group 字段；group 为 SQL 保留字故列名 feature_group） */
    @Column(name = "feature_group", nullable = false)
    private String featureGroup;

    /** ONLINE / OFFLINE */
    @Column(name = "type", nullable = false)
    private String type;

    /** INT / FLOAT / STRING / VECTOR / BOOL */
    @Column(name = "value_type", nullable = false)
    private String valueType;

    @Column(name = "description", nullable = false)
    private String description = "";

    /** 数据源表（血缘 SOURCE 节点锚 src-{source}） */
    @Column(name = "source", nullable = false)
    private String source;

    /** DRAFT / REGISTERED / PUBLISHED / DEPRECATED */
    @Column(name = "status", nullable = false)
    private String status = "DRAFT";

    @Column(name = "owner", nullable = false)
    private String owner;

    @Column(name = "online_serving", nullable = false)
    private Boolean onlineServing = false;

    /** 特征有效期（可空，如 30天） */
    @Column(name = "ttl")
    private String ttl;

    @Column(name = "call_count_30d", nullable = false)
    private Long callCount30d = 0L;

    /** 新鲜度（如 T+0 小时级 / T+1 天级） */
    @Column(name = "freshness", nullable = false)
    private String freshness = "T+1";

    @Column(name = "version", nullable = false)
    private String version = "1.0.0";

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
