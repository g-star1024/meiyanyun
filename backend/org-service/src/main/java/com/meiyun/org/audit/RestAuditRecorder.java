package com.meiyun.org.audit;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过 REST 调用 audit-service（:8084）追加审计。失败仅记录日志，不阻断主流程（审计异步补偿）。
 * 服务间调用携带 X-Internal-Token（系统身份），audit-service 不接受匿名审计写入。
 */
@Component
public class RestAuditRecorder implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(RestAuditRecorder.class);

    private final RestTemplate restTemplate;

    @Value("${audit.service.url:http://127.0.0.1:8084}")
    private String auditBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public RestAuditRecorder(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public void record(String bizType, String txnNo, String actor, String action, String payload) {
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
        }
    }
}
