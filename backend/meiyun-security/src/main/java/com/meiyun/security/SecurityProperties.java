package com.meiyun.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 安全配置（meiyun.security.*）。
 *
 * @param secret        HS256 共享密钥（7 服务一致；生产经环境变量 MEIYUN_JWT_SECRET 注入）
 * @param ttl           token 有效期，默认 12 小时
 * @param devLogin      开发期免密登录开关（/api/org/auth/dev-login），生产必须关闭
 * @param internalToken 服务间内部调用令牌（请求头 X-Internal-Token），命中即按系统身份放行；
 *                      生产经环境变量 MEIYUN_INTERNAL_TOKEN 注入
 * @param publicPaths   公共路径（Ant 风格，无需登录即可访问）
 */
@ConfigurationProperties(prefix = "meiyun.security")
public class SecurityProperties {

    private String secret = "meiyun-dev-jwt-secret-please-change-in-prod-0123456789";
    private Duration ttl = Duration.ofHours(12);
    private boolean devLogin = false;
    private String internalToken = "meiyun-dev-internal-token-please-change-in-prod";
    private List<String> publicPaths = new ArrayList<>(List.of(
            "/api/org/auth/login",
            "/api/org/auth/dev-login",
            "/actuator/health",
            "/error"));

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public boolean isDevLogin() {
        return devLogin;
    }

    public void setDevLogin(boolean devLogin) {
        this.devLogin = devLogin;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }
}
