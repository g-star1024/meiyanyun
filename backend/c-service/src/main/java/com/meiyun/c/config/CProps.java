package com.meiyun.c.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * C 端独立配置（meiyun.c.* 命名空间，与 B 端 meiyun.security.* 物理隔离）。
 * 默认值与 application.yml 对齐，环境变量可覆盖。
 */
@ConfigurationProperties(prefix = "meiyun.c")
public class CProps {

    /** C 端 JWT 签名密钥（HS256，须 ≥32 字节），独立于 B 端 meiyun.security.jwt-secret */
    private String jwtSecret = "meiyun-dev-c-jwt-secret-please-change-in-prod-01";

    /** C 端 token 有效期（ISO-8601 Duration，默认 720h=30 天，移动端长会话） */
    private String jwtTtl = "720h";

    /** 开发期手机号快捷登录开关（默认 false，关闭时端点 404 隐身；生产严禁开启） */
    private boolean devLoginEnabled = false;

    /** C 端单租户标识（请求头 X-Tenant-Id 必须等于此值，否则 403） */
    private String tenantId = "meiyun-demo";

    /** 服务间内部令牌（与 meiyun-security internal-token 一致，用于审计追加通道） */
    private String internalToken = "meiyun-dev-internal-token-please-change-in-prod";

    /** 微信小程序配置（C-B1 走环境变量注入；appid/secret 为空=未配置→wechat-login 如实 502；配置窗口后端化留 §7） */
    private Wechat wechat = new Wechat();

    /** 品牌与展示配置（GET /api/c/mp/config 返回源，前端 api/config.ts RemoteConfig 契约） */
    private Brand brand = new Brand();

    public static class Wechat {
        /** 微信小程序 AppID（空=未配置） */
        private String appid = "";
        /** 微信小程序 AppSecret（空=未配置） */
        private String secret = "";
        /** 微信支付开关（C-B5 支付接通后翻 true） */
        private boolean payEnabled = false;

        public String getAppid() { return appid; }
        public void setAppid(String appid) { this.appid = appid; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public boolean isPayEnabled() { return payEnabled; }
        public void setPayEnabled(boolean payEnabled) { this.payEnabled = payEnabled; }
    }

    public static class Brand {
        private String brandName = "美研云";
        private String servicePhone = "400-000-0000";
        private String themeColor = "#ff6b9e";
        private String notice = "";
        private boolean pointsMallEnabled = true;
        private boolean inviteEnabled = true;

        public String getBrandName() { return brandName; }
        public void setBrandName(String brandName) { this.brandName = brandName; }
        public String getServicePhone() { return servicePhone; }
        public void setServicePhone(String servicePhone) { this.servicePhone = servicePhone; }
        public String getThemeColor() { return themeColor; }
        public void setThemeColor(String themeColor) { this.themeColor = themeColor; }
        public String getNotice() { return notice; }
        public void setNotice(String notice) { this.notice = notice; }
        public boolean isPointsMallEnabled() { return pointsMallEnabled; }
        public void setPointsMallEnabled(boolean pointsMallEnabled) { this.pointsMallEnabled = pointsMallEnabled; }
        public boolean isInviteEnabled() { return inviteEnabled; }
        public void setInviteEnabled(boolean inviteEnabled) { this.inviteEnabled = inviteEnabled; }
    }

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public String getJwtTtl() { return jwtTtl; }
    public void setJwtTtl(String jwtTtl) { this.jwtTtl = jwtTtl; }
    public boolean isDevLoginEnabled() { return devLoginEnabled; }
    public void setDevLoginEnabled(boolean devLoginEnabled) { this.devLoginEnabled = devLoginEnabled; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) { this.internalToken = internalToken; }
    public Wechat getWechat() { return wechat; }
    public void setWechat(Wechat wechat) { this.wechat = wechat; }
    public Brand getBrand() { return brand; }
    public void setBrand(Brand brand) { this.brand = brand; }
}
