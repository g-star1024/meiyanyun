package com.meiyun.c.security;

import com.meiyun.c.config.CProps;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C 端 JWT 工具：照抄 B 端 meiyun-security JwtTokenUtil 的 JDK 原生 HS256 零依赖模式，
 * 但密钥独立（meiyun.c.jwt-secret）、claims 独立（sub=openid/customerId/dev/iat/exp），
 * 与 B 端 staff token 互不相认（B/C 隔离红线，DESIGN-C §〇.1）。
 */
@Component
public class CJwtUtil {

    private final CProps props;
    private byte[] secretBytes;
    private long ttlMillis;

    public CJwtUtil(CProps props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        this.secretBytes = props.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (this.secretBytes.length < 32) {
            throw new IllegalStateException("meiyun.c.jwt-secret 长度须 ≥32 字节（HS256 安全下限）");
        }
        this.ttlMillis = Duration.parse("PT" + normalizeDuration(props.getJwtTtl())).toMillis();
    }

    /** 支持 "720h" / "30d" 之外的纯 ISO-8601 写法（已带 P 前缀则原样用） */
    private static String normalizeDuration(String ttl) {
        String t = ttl == null ? "720h" : ttl.trim();
        if (t.startsWith("P") || t.startsWith("p")) {
            return t.substring(1);
        }
        return t;
    }

    /** 签发 C 端 token：sub=openid，customerId 可空（C-B3 绑定后才回填），dev 标记开发期身份 */
    public String issue(String openid, String customerId, boolean dev) {
        long now = System.currentTimeMillis();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", openid);
        if (customerId != null && !customerId.isEmpty()) {
            claims.put("customerId", customerId);
        }
        claims.put("dev", dev);
        claims.put("iat", now / 1000);
        claims.put("exp", (now + ttlMillis) / 1000);
        String h = b64url(toJson(header));
        String p = b64url(toJson(claims));
        return h + "." + p + "." + sign(h + "." + p);
    }

    /**
     * 验签并解析 claims；任何失败（结构/签名/过期）一律抛 CAuthException("登录凭证无效或已过期")→401。
     */
    public Map<String, Object> parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new CAuthException("登录凭证无效或已过期");
            }
            String expect = sign(parts[0] + "." + parts[1]);
            if (!constantTimeEquals(expect, parts[2])) {
                throw new CAuthException("登录凭证无效或已过期");
            }
            Map<String, Object> claims = fromJson(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            Object exp = claims.get("exp");
            long expSec = exp instanceof Number ? ((Number) exp).longValue() : Long.parseLong(String.valueOf(exp));
            if (expSec * 1000 < System.currentTimeMillis()) {
                throw new CAuthException("登录凭证无效或已过期");
            }
            return claims;
        } catch (CAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new CAuthException("登录凭证无效或已过期");
        }
    }

    public static class CAuthException extends RuntimeException {
        public CAuthException(String message) { super(message); }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("C 端 token 签名失败", e);
        }
    }

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    /** 极简 JSON 序列化（仅支持 String/Number/Boolean 值，claims/header 足够用，零依赖） */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append('"').append(':');
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /** 极简 JSON 解析（仅支持扁平 String/Number/Boolean 值，claims 足够用，零依赖） */
    private static Map<String, Object> fromJson(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        String s = json.trim();
        if (s.length() < 2 || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') {
            throw new IllegalArgumentException("claims 结构非法");
        }
        s = s.substring(1, s.length() - 1).trim();
        if (s.isEmpty()) {
            return map;
        }
        int i = 0;
        while (i < s.length()) {
            int[] keyEnd = readString(s, i);
            String key = s.substring(i + 1, keyEnd[0]);
            i = skipWs(s, keyEnd[1] + 1);
            if (i >= s.length() || s.charAt(i) != ':') {
                throw new IllegalArgumentException("claims 结构非法");
            }
            i = skipWs(s, i + 1);
            Object value;
            if (i < s.length() && s.charAt(i) == '"') {
                int[] valEnd = readString(s, i);
                value = s.substring(i + 1, valEnd[0]);
                i = valEnd[1] + 1;
            } else {
                int j = i;
                while (j < s.length() && s.charAt(j) != ',') {
                    j++;
                }
                String raw = s.substring(i, j).trim();
                if ("true".equals(raw) || "false".equals(raw)) {
                    value = Boolean.parseBoolean(raw);
                } else {
                    value = Long.parseLong(raw);
                }
                i = j;
            }
            map.put(key, value);
            i = skipWs(s, i);
            if (i < s.length()) {
                if (s.charAt(i) != ',') {
                    throw new IllegalArgumentException("claims 结构非法");
                }
                i = skipWs(s, i + 1);
            }
        }
        return map;
    }

    private static int[] readString(String s, int start) {
        if (s.charAt(start) != '"') {
            throw new IllegalArgumentException("claims 结构非法");
        }
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return new int[]{i, i};
            } else {
                i++;
            }
        }
        throw new IllegalArgumentException("claims 结构非法");
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}
