package com.meiyun.finance;

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
 * finance 侧审计客户端：通过 REST 调用 audit-service（:8084）追加审计。
 * 失败仅 log.error 不阻断落账主流程（审计异步补偿）；服务间携带 X-Internal-Token 系统身份。
 */
@Component
public class FinanceAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(FinanceAuditRecorder.class);

    private final RestTemplate restTemplate;

    @Value("${audit.service.url:http://127.0.0.1:8084}")
    private String auditBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public FinanceAuditRecorder(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** payload 必须为合法 JSON 字符串（audit_log.payload 为 jsonb，纯文本会被 audit 服务拒收）。 */
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
