package com.meiyun.marketing;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * marketing → org 外部依赖配置窗口客户端（P5-B57 卡2）。
 *
 * <p>GET org /api/org/internal/integrations/snapshot（X-Internal-Token 系统身份），
 * 60 秒 TTL 内存快照（volatile，不引新依赖、不缓存到磁盘）；拉取失败时 10 分钟内沿用上一份
 * good 快照并 warn，超过宽限或从无快照则回退 env/@Value（由调用方传入 envFallback）。
 * 全程 try/catch 软降级，任何异常不抛给回传验签主链路；env 也空时由调用方保持 503 fail-closed。
 */
@Component
public class IntegrationConfigClient {

    private static final Logger log = LoggerFactory.getLogger(IntegrationConfigClient.class);

    private static final long TTL_MILLIS = 60_000L;
    private static final long GRACE_MILLIS = 600_000L;

    private final RestTemplate restTemplate;

    @Value("${org.service.url:http://127.0.0.1:8086}")
    private String orgBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    private volatile Map<String, SnapshotLine> snapshot = Map.of();
    private volatile long fetchedAt = 0L;

    public IntegrationConfigClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 通知网关 URL（marketing 本批无 URL 消费点，预留同构方法）：库启用值 → env 兜底 → 空。 */
    public String resolveUrl(String code, String envFallback) {
        SnapshotLine line = current().get(code);
        if (line != null && line.enabled() && notBlank(line.baseUrl())) {
            return line.baseUrl();
        }
        return envFallback == null ? null : envFallback.isBlank() ? null : envFallback;
    }

    /** 广告 HMAC 密钥：库启用值 → env 兜底 → 空（调用方保持 503 fail-closed）。 */
    public String resolveSecret(String code, String envFallback) {
        SnapshotLine line = current().get(code);
        if (line != null && line.enabled() && notBlank(line.secret())) {
            return line.secret();
        }
        return envFallback == null ? null : envFallback.isBlank() ? null : envFallback;
    }

    /** 免签开关：库启用且 true → true；否则 env=true → true；否则 false（fail-closed）。 */
    public boolean resolveSwitch(String code, boolean envFallback) {
        SnapshotLine line = current().get(code);
        if (line != null && line.enabled() && Boolean.TRUE.equals(line.boolValue())) {
            return true;
        }
        return envFallback;
    }

    /** 取当前有效快照；TTL 过期则尝试刷新，刷新失败在宽限期内沿用旧快照。 */
    private Map<String, SnapshotLine> current() {
        long now = System.currentTimeMillis();
        if (now - fetchedAt < TTL_MILLIS) {
            return snapshot;
        }
        synchronized (this) {
            if (now - fetchedAt < TTL_MILLIS) {
                return snapshot;
            }
            Map<String, SnapshotLine> fresh = fetch();
            if (fresh != null) {
                snapshot = fresh;
                fetchedAt = now;
                return snapshot;
            }
            long age = fetchedAt == 0L ? Long.MAX_VALUE : now - fetchedAt;
            if (age <= GRACE_MILLIS) {
                log.warn("org 集成配置快照刷新失败，10 分钟宽限内沿用上一份快照");
                return snapshot;
            }
            log.warn("org 集成配置快照不可用且超出宽限，本次回退 env/@Value 兜底");
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, SnapshotLine> fetch() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            List<Map<String, Object>> rows = restTemplate.exchange(
                    orgBaseUrl + "/api/org/internal/integrations/snapshot",
                    HttpMethod.GET, new HttpEntity<>(headers), List.class).getBody();
            if (rows == null) {
                return null;
            }
            Map<String, SnapshotLine> out = new HashMap<>();
            for (Map<String, Object> row : rows) {
                Object code = row.get("code");
                if (code == null) {
                    continue;
                }
                out.put(code.toString(), new SnapshotLine(
                        Boolean.TRUE.equals(row.get("enabled")),
                        str(row.get("baseUrl")),
                        str(row.get("secret")),
                        row.get("boolValue") instanceof Boolean b ? b : null,
                        row.get("updatedAt") == null ? null : parseTime(row.get("updatedAt"))));
            }
            return out;
        } catch (Exception e) {
            log.warn("拉取 org 集成配置快照失败：{}", e.getMessage());
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static OffsetDateTime parseTime(Object o) {
        try {
            return OffsetDateTime.parse(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private record SnapshotLine(boolean enabled, String baseUrl, String secret,
                                Boolean boolValue, OffsetDateTime updatedAt) {
    }
}
