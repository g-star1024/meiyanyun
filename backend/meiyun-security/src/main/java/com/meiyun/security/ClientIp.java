package com.meiyun.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 真实客户端 IP 解析（L134 平台治理）。
 *
 * <p>请求拓扑：浏览器 → nginx（注入 X-Real-IP / X-Forwarded-For）→ 国密 Go 网关（规范化）→ 后端服务。
 * 网关亦对直连请求（无前置反代）以 TCP 对端兜底，故后端收到的请求必带可用 IP 头。
 *
 * <p>解析优先级：
 * <ol>
 *     <li>X-Forwarded-For 首个条目（标准链路头，逗号分隔，左most 为最初发起方）；</li>
 *     <li>X-Real-IP（nginx/网关写入的直连客户端）；</li>
 *     <li>{@link HttpServletRequest#getRemoteAddr()}（容器 TCP 对端，最后兜底）。</li>
 * </ol>
 * 所有候选均做空白裁剪；极端情况下三者都缺失时返回 "unknown"，保证审计 payload.ip 永不为 null/"web" 占位。
 */
public final class ClientIp {

    private static final String UNKNOWN = "unknown";

    private ClientIp() {
    }

    public static String resolve(HttpServletRequest request) {
        String ip = firstNonBlank(request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr());
        return ip == null ? UNKNOWN : ip;
    }

    /**
     * XFF 头形如 "client, proxy1, proxy2"，首段才是真实客户端；
     * 单值头（X-Real-IP / RemoteAddr）无逗号，split 首段即其本身。
     */
    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String first = candidate.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return null;
    }
}
