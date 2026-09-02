package com.meiyun.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JWT（HS256）签发/验签工具，全部 JDK 原生实现（零 jjwt/security 依赖）。
 *
 * Token 结构：base64url(header).base64url(payload).base64url(HMACSHA256(header.payload, secret))
 * payload 为自包含 claims：sub（工号）/ name / roles / store / scope / perms / dev / iat / exp。
 * 各服务共享同一 secret 本地验签，服务自治、无状态。
 */
public class JwtTokenUtil {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final byte[] secret;
    private final Duration ttl;

    public JwtTokenUtil(String secret, Duration ttl) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT 密钥长度不足 32 字节（HS256 安全下限）");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
    }

    /** 签发 token。perms 为权限码集合（超管传 List.of("*")）。 */
    public String issue(LoginUser user) {
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.staffId());
        payload.put("name", user.staffName());
        payload.put("roles", user.roles());
        if (user.storeCode() != null) payload.put("store", user.storeCode());
        payload.put("scope", user.scope());
        payload.put("perms", user.perms());
        if (user.region() != null && !user.region().isBlank()) payload.put("region", user.region());
        if (user.stores() != null && !user.stores().isEmpty()) payload.put("stores", user.stores());
        payload.put("dev", user.devLogin());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", now.plus(ttl).getEpochSecond());

        String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body = B64.encodeToString(toJson(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + body;
        String sig = B64.encodeToString(hmac(signingInput));
        return signingInput + "." + sig;
    }

    /**
     * 验签并解析。验签失败/过期/格式非法均抛 {@link JwtAuthException}（转 401）。
     */
    public LoginUser parse(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtAuthException("登录凭证格式非法");
        }
        String expectedSig = B64.encodeToString(hmac(parts[0] + "." + parts[1]));
        if (!constantTimeEquals(expectedSig, parts[2])) {
            throw new JwtAuthException("登录凭证签名无效");
        }
        Map<String, Object> payload;
        try {
            payload = MAPPER.readValue(B64_DEC.decode(parts[1]), MAP_TYPE);
        } catch (Exception e) {
            throw new JwtAuthException("登录凭证内容无法解析");
        }
        long exp = asLong(payload.get("exp"));
        if (Instant.now().getEpochSecond() >= exp) {
            throw new JwtAuthException("登录已过期，请重新登录");
        }
        try {
            return new LoginUser(
                    (String) payload.get("sub"),
                    (String) payload.get("name"),
                    asStringList(payload.get("roles")),
                    (String) payload.get("store"),
                    (String) payload.get("scope"),
                    asStringList(payload.get("perms")),
                    Boolean.TRUE.equals(payload.get("dev")),
                    (String) payload.get("region"),
                    asStringList(payload.get("stores")));
        } catch (Exception e) {
            throw new JwtAuthException("登录凭证字段缺失");
        }
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    private static String toJson(Map<String, Object> m) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("JWT payload 序列化失败", e);
        }
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** 401 未认证（缺/坏/过期 token）。 */
    public static class JwtAuthException extends RuntimeException {
        public JwtAuthException(String message) {
            super(message);
        }
    }
}
