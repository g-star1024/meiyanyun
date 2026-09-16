package com.meiyun.ai.audit;

import com.meiyun.security.AuditBoundary;
import com.meiyun.security.AuthInterceptor;
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
 * 通过 REST 调用 audit-service（:8084）追加审计。直发失败时同事务落 audit_outbox，由 audit-service 中继补偿，不阻断主流程。
 * 服务间调用携带 X-Internal-Token（系统身份）。
 */
@Component
public class RestAuditRecorder implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(RestAuditRecorder.class);

    private static final String SOURCE_SERVICE = "ai-service";

    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;

    @Value("${audit.service.url:http://127.0.0.1:8084}")
    private String auditBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public RestAuditRecorder(RestTemplate restTemplate, JdbcTemplate jdbcTemplate) {
        this.restTemplate = restTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(String bizType, String txnNo, String actor, String action, String payload) {
        // 审计边界收敛：超管代操作时 actor 强制为真实超管 realSub，payload 注入 act/realSub
        actor = AuditBoundary.resolveActor(actor);
        payload = AuditBoundary.enrichPayload(payload);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("bizType", bizType);
        body.put("txnNo", txnNo);
        body.put("actor", actor);
        body.put("action", action);
        body.put("payload", payload);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        try {
            restTemplate.postForEntity(auditBaseUrl + "/api/audit",
                    new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            log.error("审计追加失败 bizType={} txnNo={} : {}", bizType, txnNo, e.getMessage());
            try {
                jdbcTemplate.update(
                        "INSERT INTO audit_outbox(source_service, biz_type, txn_no, actor, action, payload)"
                                + " VALUES (?,?,?,?,?,?)",
                        SOURCE_SERVICE, bizType, txnNo, actor, action, payload);
            } catch (Exception ex) {
                log.error("审计补偿落库失败 bizType={} txnNo={} : {}", bizType, txnNo, ex.getMessage());
            }
        }
    }
}
