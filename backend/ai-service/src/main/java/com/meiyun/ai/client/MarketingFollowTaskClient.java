package com.meiyun.ai.client;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 跟进任务下发跨服务客户端（M3-B2 / DESIGN-M3 §3 D3-1/2）：
 * 流失干预登记（churn）/ 复购跟进登记（repurchase）置位成功后旁路建真实跟进任务 →
 * marketing-service {@code POST /api/marketing/internal/follow-tasks}（X-Internal-Token 系统身份）。
 *
 * <p>软降级（DESIGN §6）：marketing 域故障不阻断 ai 侧登记，返回 false 留痕；
 * 重试由 idemKey（source:sourceId:customerId:date，D8）部分唯一索引防重，同日重放不重复建单。
 */
@Component
public class MarketingFollowTaskClient {

    private static final Logger log = LoggerFactory.getLogger(MarketingFollowTaskClient.class);

    private final RestTemplate restTemplate;

    @Value("${marketing.service.url:http://127.0.0.1:8088}")
    private String marketingBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public MarketingFollowTaskClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 下发跟进任务；成功 true，marketing 域不可用 false（软降级，不阻断 ai 侧登记）。 */
    public boolean createFollowTask(String source, String sourceId, String customerId, String customerName,
                                    String customerLevel, String type, String content, String priority,
                                    String storeCode, String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("source", source);
            body.put("sourceId", sourceId);
            body.put("customerId", customerId);
            body.put("customerName", customerName);
            body.put("customerLevel", customerLevel);
            body.put("type", type);
            body.put("content", content);
            body.put("priority", priority);
            body.put("storeCode", storeCode);
            body.put("idemKey", idemKey);
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(marketingBaseUrl + "/api/marketing/internal/follow-tasks",
                    HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            return true;
        } catch (Exception e) {
            log.warn("跟进任务下发失败（软降级不阻断 ai 侧登记，重放由 idemKey 防重），idemKey={} : {}",
                    idemKey, e.getMessage());
            return false;
        }
    }
}
