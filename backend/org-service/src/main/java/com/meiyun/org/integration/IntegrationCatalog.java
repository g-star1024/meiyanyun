package com.meiyun.org.integration;

import java.util.Optional;

/**
 * P0 外部依赖固定目录（20 项），与 V34/V35/V36/V89/V90/V93 迁移 INSERT 同码同名，为目录第二真源。
 * P0 不开放自由新增接入点；P1/P2 新接入点以「新迁移 + 枚举扩充」追加。
 */
public enum IntegrationCatalog {

    NOTIFY_SMS_GATEWAY("NOTIFY_GATEWAY", "短信通知网关 webhook", "URL"),
    NOTIFY_WECHAT_GATEWAY("NOTIFY_GATEWAY", "企业微信通知 webhook", "URL"),
    NOTIFY_EMAIL_GATEWAY("NOTIFY_GATEWAY", "邮件通知网关 webhook", "URL"),
    AD_SECRET_DOUYIN("AD_CHANNEL", "抖音/巨量回传签名密钥", "SECRET"),
    AD_SECRET_RED("AD_CHANNEL", "小红书回传签名密钥", "SECRET"),
    AD_SECRET_MEITUAN("AD_CHANNEL", "美团回传签名密钥", "SECRET"),
    AD_DEV_NO_AUTH("AD_CHANNEL", "广告回传免签（仅联调）", "SWITCH"),
    NOTIFY_GLOBAL_QUIET("NOTIFY_GATEWAY", "全局免打扰时段", "QUIET_WINDOW"),
    NOTIFY_SMS_DIRECT("NOTIFY_GATEWAY", "短信直连官方 API（阿里云）", "SWITCH"),
    NOTIFY_SMS_SECRET("NOTIFY_GATEWAY", "短信直连密钥（阿里云 AccessKeySecret）", "SECRET"),
    NOTIFY_EMAIL_DIRECT("NOTIFY_GATEWAY", "邮件直连 SMTP 服务", "SWITCH"),
    NOTIFY_EMAIL_SECRET("NOTIFY_GATEWAY", "邮件直连密钥（SMTP 授权码）", "SECRET"),
    NOTIFY_WECHAT_DIRECT("NOTIFY_GATEWAY", "企业微信直连官方 API（应用消息）", "SWITCH"),
    NOTIFY_WECHAT_SECRET("NOTIFY_GATEWAY", "企微直连密钥（应用 secret）", "SECRET"),

    // 棒⑧卡3 电子签接入位（V89 迁移同码播种）：厂商无关适配层消费，发送调用与回调验签共用密钥
    ESIGN_DIRECT("ESIGN", "电子签直连厂商 API（接入位）", "SWITCH"),
    ESIGN_SECRET("ESIGN", "电子签厂商密钥（接入调用与回调验签共用）", "SECRET"),

    // 棒⑧卡4 对象存储接入位（V90 迁移同码播种）：marketing DelegatingStorageService 消费，
    // config_json 承载 provider/endpoint/bucket/region/accessKey，secretKey 独立 SECRET 行
    STORAGE_DIRECT("STORAGE", "对象存储直连（S3/MinIO 接入位）", "SWITCH"),
    STORAGE_SECRET("STORAGE", "对象存储密钥（secretKey）", "SECRET"),

    // 棒⑧卡5 Kafka 事件发布接入位（V93 迁移同码播种）：marketing KafkaDomainEventPublisher 消费，
    // config_json 承载 bootstrapServers/clientIdPrefix，SASL 凭据独立 SECRET 行（PLAINTEXT 可留空）
    KAFKA_DIRECT("KAFKA", "Kafka 事件发布直连（接入位）", "SWITCH"),
    KAFKA_SECRET("KAFKA", "Kafka 密钥（SASL）", "SECRET");

    private final String category;
    private final String integrationName;
    private final String valueKind;

    IntegrationCatalog(String category, String integrationName, String valueKind) {
        this.category = category;
        this.integrationName = integrationName;
        this.valueKind = valueKind;
    }

    public String getCategory() {
        return category;
    }

    public String getIntegrationName() {
        return integrationName;
    }

    public String getValueKind() {
        return valueKind;
    }

    public static Optional<IntegrationCatalog> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String trimmed = code.trim();
        for (IntegrationCatalog item : values()) {
            if (item.name().equals(trimmed)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }
}
