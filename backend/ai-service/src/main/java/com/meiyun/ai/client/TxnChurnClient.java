package com.meiyun.ai.client;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 流失预警候选信号跨服务客户端（不直读 txn 表）：
 * 交易域 {@code /api/txn/internal/churn-candidates} 返回按客户聚合的真实已收款订单信号
 * （久未到店优先的 RFM/到店间隔/近 90 天消费下降率/最近成交日期），仅以系统身份
 * （X-Internal-Token，internal:finance-flow）取回；交易域不可用时一律降级为空列表，
 * 流失预警页引导空态而不是造数。
 */
@Component
public class TxnChurnClient {

    private static final Logger log = LoggerFactory.getLogger(TxnChurnClient.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${txn.service.url:http://127.0.0.1:8083}")
    private String txnBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public TxnChurnClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 流失候选信号；storeCode 可空（全集团），limit 由交易域兜底（默认 8、上限 20）；失败降级空列表。 */
    public List<Map<String, Object>> candidates(String storeCode, int limit) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(txnBaseUrl + "/api/txn/internal/churn-candidates")
                    .queryParam("limit", limit);
            if (storeCode != null && !storeCode.isBlank()) {
                b.queryParam("storeCode", storeCode.trim());
            }
            URI uri = b.encode(StandardCharsets.UTF_8).build().toUri();
            ResponseEntity<List<Map<String, Object>>> resp =
                    restTemplate.exchange(uri, HttpMethod.GET, entity(), LIST_MAP_TYPE);
            return resp.getBody() == null ? List.of() : resp.getBody();
        } catch (Exception e) {
            log.warn("流失候选信号拉取失败（降级为空）: {}", e.getMessage());
            return List.of();
        }
    }

    private HttpEntity<Void> entity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        return new HttpEntity<>(headers);
    }
}
