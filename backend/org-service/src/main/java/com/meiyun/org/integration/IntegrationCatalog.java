package com.meiyun.org.integration;

import java.util.Optional;

/**
 * P0 外部依赖固定目录（8 项），与 V34/V35 迁移 INSERT 同码同名，为目录第二真源。
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
    NOTIFY_GLOBAL_QUIET("NOTIFY_GATEWAY", "全局免打扰时段", "QUIET_WINDOW");

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
