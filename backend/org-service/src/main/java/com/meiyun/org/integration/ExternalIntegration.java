package com.meiyun.org.integration;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 外部依赖统一配置窗口（P5-B57 卡2，V34）：通知网关 / 广告渠道密钥 / 安全开关。
 * org-service 为平台属主单点持钥；P0 目录固定 7 行（{@link IntegrationCatalog}），禁止前端自由建项。
 *
 * <p>valueKind 三态：URL（网关 webhook，存 base_url）/ SECRET（HMAC 密钥，AES-GCM 密文+掩码）/
 * SWITCH（开关，存 bool_value）；secret_cipher 明文绝不离开 org，仅服务间内部快照端点解密下发。
 */
@Entity
@Table(name = "external_integration", uniqueConstraints =
        @UniqueConstraint(name = "uq_external_integration_code", columnNames = {"integration_code"}))
@Getter @Setter @NoArgsConstructor
public class ExternalIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "integration_code", nullable = false, length = 64)
    private String integrationCode;

    /** 分类：NOTIFY_GATEWAY | AD_CHANNEL。 */
    @Column(nullable = false, length = 32)
    private String category;

    @Column(name = "integration_name", nullable = false, length = 128)
    private String integrationName;

    /** 值类型：URL | SECRET | SWITCH。 */
    @Column(name = "value_kind", nullable = false, length = 16)
    private String valueKind;

    @Column(name = "base_url", length = 512)
    private String baseUrl;

    @Column(name = "secret_cipher", columnDefinition = "TEXT")
    private String secretCipher;

    @Column(name = "secret_mask", length = 80)
    private String secretMask;

    @Column(name = "bool_value")
    private Boolean boolValue;

    @Column(nullable = false)
    private boolean enabled = false;

    /** 协议扩展位（P1 verifyMode 等），P0 可空；JDBC 已带 stringtype=unspecified，直接映射 String。 */
    @Column(name = "config_json", columnDefinition = "jsonb")
    private String configJson;

    /** 「未配置影响」诚实文案，页面常驻展示。 */
    @Column(length = 256)
    private String remark;

    @Column(name = "last_test_at")
    private OffsetDateTime lastTestAt;

    @Column(name = "last_test_ok")
    private Boolean lastTestOk;

    @Column(name = "last_test_msg", length = 256)
    private String lastTestMsg;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;
}
