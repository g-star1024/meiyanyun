package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * T2-01 数据源注册（棒⑥卡7，表 data_source / V72）。
 * 注册登记真源化；接入运行时归 v2 移交（DESIGN-T2 §6），故无运行态字段。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "data_source")
public class DataSource {

    /** 类型三值。 */
    public static final String TYPE_CDC = "CDC";
    public static final String TYPE_KAFKA = "KAFKA";
    public static final String TYPE_THIRD_PARTY = "THIRD_PARTY";

    /** 状态三值。 */
    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "endpoint", length = 200)
    private String endpoint;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "owner", nullable = false, length = 50)
    private String owner;

    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (status == null) status = STATUS_REGISTERED;
        if (description == null) description = "";
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
