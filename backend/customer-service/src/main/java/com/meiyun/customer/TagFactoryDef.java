package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * T2-B2 标签工厂-标签定义（DESIGN-T2 T2-03，表 tag_factory_def / V67）。
 * 治理对象为集团级标签资产，非单店行，故无 store_code 维度。
 * versions/consumers/tags 为 jsonb 文本列，String 映射（IoTask.java L61 先例），由 Service 层 Jackson 解析。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tag_factory_def")
public class TagFactoryDef {

    /** 加工方式三值。 */
    public static final String TYPE_SQL = "SQL";
    public static final String TYPE_RULE = "RULE";
    public static final String TYPE_ML = "ML";

    /** 状态五值。 */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_OFFLINE = "OFFLINE";
    public static final String STATUS_PENDING = "PENDING_APPROVAL";

    /** 敏感等级三值。 */
    public static final String SENS_PUBLIC = "PUBLIC";
    public static final String SENS_INTERNAL = "INTERNAL";
    public static final String SENS_SENSITIVE = "SENSITIVE";

    /** 值类型四值。 */
    public static final String VALUE_ENUM = "ENUM";
    public static final String VALUE_NUMBER = "NUMBER";
    public static final String VALUE_BOOLEAN = "BOOLEAN";
    public static final String VALUE_DATE = "DATE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "type", nullable = false, length = 8)
    private String type;

    @Column(name = "sensitivity", nullable = false, length = 16)
    private String sensitivity;

    @Column(name = "value_type", nullable = false, length = 16)
    private String valueType;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "sql", nullable = false, columnDefinition = "text")
    private String sql;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "cover_count", nullable = false)
    private Integer coverCount;

    @Column(name = "refresh_cron", nullable = false, length = 32)
    private String refreshCron;

    @Column(name = "last_compute_at")
    private OffsetDateTime lastComputeAt;

    @Column(name = "versions", columnDefinition = "jsonb")
    private String versions;

    @Column(name = "consumers", columnDefinition = "jsonb")
    private String consumers;

    @Column(name = "tags", columnDefinition = "jsonb")
    private String tags;

    @Column(name = "owner", nullable = false, length = 64)
    private String owner;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (category == null) category = "";
        if (sensitivity == null) sensitivity = SENS_PUBLIC;
        if (description == null) description = "";
        if (sql == null) sql = "";
        if (status == null) status = STATUS_DRAFT;
        if (coverCount == null) coverCount = 0;
        if (refreshCron == null) refreshCron = "";
        if (versions == null) versions = "[]";
        if (consumers == null) consumers = "[]";
        if (tags == null) tags = "[]";
        if (owner == null) owner = "";
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }
}
