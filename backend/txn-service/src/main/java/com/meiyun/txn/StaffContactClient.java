package com.meiyun.txn;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * txn → org 员工联系方式快照客户端（棒⑧卡1 三通道直连收件人映射）。
 *
 * <p>GET org /api/org/internal/staff/contact-snapshot（X-Internal-Token 系统身份），
 * 60 秒 TTL 内存快照（volatile，不引新依赖）；拉取失败时 10 分钟内沿用上一份 good 快照并 warn，
 * 超过宽限或从无快照则返回空表——调用方按 SKIPPED 诚实降级（直连收件人缺失，不回落 webhook 中继）。
 * 全程 try/catch 软降级，任何异常不抛给通知投递主链路。
 */
@Component
public class StaffContactClient {

    private static final Logger log = LoggerFactory.getLogger(StaffContactClient.class);

    private static final long TTL_MILLIS = 60_000L;
    private static final long GRACE_MILLIS = 600_000L;

    private final RestTemplate restTemplate;

    @Value("${org.service.url:http://127.0.0.1:8086}")
    private String orgBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    private volatile Map<String, Contact> snapshot = Map.of();
    private volatile long fetchedAt = 0L;

    public StaffContactClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 员工联系方式：命中快照返回（字段可空，由调用方按通道判空）；工号未登记或无快照返回 null。 */
    public Contact contactOf(String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return null;
        }
        return current().get(staffId.trim());
    }

    private Map<String, Contact> current() {
        long now = System.currentTimeMillis();
        if (now - fetchedAt < TTL_MILLIS) {
            return snapshot;
        }
        synchronized (this) {
            if (now - fetchedAt < TTL_MILLIS) {
                return snapshot;
            }
            Map<String, Contact> fresh = fetch();
            if (fresh != null) {
                snapshot = fresh;
                fetchedAt = now;
                return snapshot;
            }
            long age = fetchedAt == 0L ? Long.MAX_VALUE : now - fetchedAt;
            if (age <= GRACE_MILLIS) {
                log.warn("org 员工联系方式快照刷新失败，10 分钟宽限内沿用上一份快照");
                return snapshot;
            }
            log.warn("org 员工联系方式快照不可用且超出宽限，本次按收件人缺失 SKIPPED 处理");
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Contact> fetch() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            List<Map<String, Object>> rows = restTemplate.exchange(
                    orgBaseUrl + "/api/org/internal/staff/contact-snapshot",
                    HttpMethod.GET, new HttpEntity<>(headers), List.class).getBody();
            if (rows == null) {
                return null;
            }
            Map<String, Contact> out = new HashMap<>();
            for (Map<String, Object> row : rows) {
                Object id = row.get("staffId");
                if (id == null) {
                    continue;
                }
                out.put(id.toString(), new Contact(
                        str(row.get("phone")), str(row.get("email")), str(row.get("wecomUserid"))));
            }
            return out;
        } catch (Exception e) {
            log.warn("拉取 org 员工联系方式快照失败：{}", e.getMessage());
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 员工联系方式三元组（手机号/邮箱/企微 userid；任一可空，缺即该通道 SKIPPED 诚实降级）。 */
    public record Contact(String phone, String email, String wecomUserid) {
    }
}
