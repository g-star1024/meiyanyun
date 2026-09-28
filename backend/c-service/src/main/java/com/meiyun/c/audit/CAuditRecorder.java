package com.meiyun.c.audit;

import com.meiyun.c.config.CProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C 端审计追加器（照 marketing-service RestAuditRecorder 模板）：
 * 主通道 RestTemplate POST {audit.service.url}/api/audit 带 X-Internal-Token 系统身份头；
 * 失败时 JdbcTemplate 落 audit_outbox 补偿（V30，status=PENDING 由对账批捞起）。
 * 包名隔离：本类在 com.meiyun.c 下，不依赖 meiyun-security（B/C 隔离红线）。
 */
@Component
public class CAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(CAuditRecorder.class);

    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final CProps props;

    @Value("${meiyun.audit.service-url:http://127.0.0.1:8084}")
    private String auditServiceUrl;

    public CAuditRecorder(RestTemplate restTemplate, JdbcTemplate jdbcTemplate, CProps props) {
        this.restTemplate = restTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
    }

    /** C-B1 认证域：bizType=C_AUTH、action=LOGIN、actor=C:<openid> */
    public void recordLogin(String openid, boolean dev, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("openid", openid);
        payload.put("dev", dev);
        payload.put("detail", detail);
        record("C_AUTH", "C-LOGIN-" + openid, "C:" + openid, "LOGIN", payload);
    }

    public void record(String bizType, String txnNo, String actor, String action, Map<String, Object> payload) {
        // audit-service AppendRequest 契约（铁律 0 实证 AuditController）：bizType/txnNo/actor/action 平铺，
        // payload 为 @NotBlank String（JSON 文本），无 sourceService 字段（服务来源由 outbox/链路侧登记）。
        String payloadJson = toJson(payload == null ? Map.of() : payload);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bizType", bizType);
        body.put("txnNo", txnNo);
        body.put("actor", actor);
        body.put("action", action);
        body.put("payload", payloadJson);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", props.getInternalToken());
            restTemplate.postForEntity(auditServiceUrl + "/api/audit",
                    new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            log.warn("审计主通道失败，落 audit_outbox 补偿：{}", e.getMessage());
            try {
                jdbcTemplate.update(
                        "INSERT INTO audit_outbox (source_service, biz_type, txn_no, actor, action, payload, status) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING')",
                        "c-service", bizType, txnNo, actor, action, toJson(body));
            } catch (Exception ex) {
                log.error("audit_outbox 补偿亦失败，审计丢失风险：{}", ex.getMessage());
            }
        }
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            appendValue(sb, e.getValue());
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map) {
            sb.append(toJson((Map<String, Object>) v));
        } else {
            sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
    }
}
