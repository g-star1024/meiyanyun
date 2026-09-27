package com.meiyun.org.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * T3 数据中台 连接器目录（DESIGN-T3 §三，表 integration_connector / V70）。
 * 红线：单向镜像（UNIDIRECTIONAL）绝不反向写资金池；status 仅由真实探测/同步驱动，
 * 禁止伪造 CONNECTED（DESIGN-T3 §二 红线②）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "integration_connector")
public class IntegrationConnector {

    /** 类型七枚举。 */
    public static final String TYPE_PAYMENT = "PAYMENT";
    public static final String TYPE_INSURANCE = "INSURANCE";
    public static final String TYPE_WECOM = "WECOM";
    public static final String TYPE_TAX = "TAX";
    public static final String TYPE_ADS = "ADS";
    public static final String TYPE_KINGDEE = "KINGDEE";
    public static final String TYPE_YONYOU = "YONYOU";

    /** 状态三值（SYNCING 为运行瞬时态不落库，前端 pending 投影）。 */
    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_DISCONNECTED = "DISCONNECTED";
    public static final String STATUS_ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "endpoint", nullable = false, length = 256)
    private String endpoint;

    @Column(name = "credential_key", length = 128)
    private String credentialKey;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    @Column(name = "last_error", length = 256)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
