package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * T2-B3 数据服务目录（DESIGN-T2 T2-04，表 data_service / V68）。
 * 集团级数据资产（API/数据集），非单店行，故无 store_code 维度。
 * fields/tags 为 jsonb 文本列，String 映射（TagFactoryDef.java L90 先例），由 Service 层 Jackson 解析。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "data_service")
public class DataService {

    /** 服务类型二值。 */
    public static final String TYPE_API = "API";
    public static final String TYPE_DATASET = "DATASET";

    /** 状态三值。 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_DEPRECATED = "DEPRECATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "endpoint", length = 200)
    private String endpoint;

    @Column(name = "method", length = 10)
    private String method;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "owner", nullable = false, length = 50)
    private String owner;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "call_count_24h", nullable = false)
    private Integer callCount24h;

    @Column(name = "avg_latency", nullable = false)
    private Integer avgLatency;

    @Column(name = "error_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal errorRate;

    @Column(name = "fields", columnDefinition = "jsonb")
    private String fields;

    @Column(name = "tags", columnDefinition = "jsonb")
    private String tags;

    @Column(name = "version", nullable = false, length = 20)
    private String version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (description == null) description = "";
        if (status == null) status = STATUS_DRAFT;
        if (callCount24h == null) callCount24h = 0;
        if (avgLatency == null) avgLatency = 0;
        if (errorRate == null) errorRate = BigDecimal.ZERO;
        if (fields == null) fields = "[]";
        if (tags == null) tags = "[]";
        if (version == null) version = "v0.1";
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
