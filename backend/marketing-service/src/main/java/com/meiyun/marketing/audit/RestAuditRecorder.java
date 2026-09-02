package com.meiyun.marketing.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过 REST 调用 audit-service（:8084）追加审计。失败仅记录日志，不阻断主流程（审计异步补偿）。
 */
@Component
public class RestAuditRecorder implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(RestAuditRecorder.class);

    private final RestTemplate restTemplate;

    @Value("${audit.service.url:http://127.0.0.1:8084}")
    private String auditBaseUrl;

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
        try {
            restTemplate.postForEntity(auditBaseUrl + "/api/audit", body, Map.class);
        } catch (Exception e) {
            log.error("审计追加失败 bizType={} txnNo={} : {}", bizType, txnNo, e.getMessage());
        }
    }
}
