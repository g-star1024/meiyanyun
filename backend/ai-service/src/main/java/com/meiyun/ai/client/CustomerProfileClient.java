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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户画像上下文跨服务客户端（不直读 customer 表）：
 * <ul>
 *   <li>单客户画像上下文 → customer-service {@code /api/customer/internal/profile-context/{id}}</li>
 *   <li>关键词搜索候选 → {@code /api/customer/internal/profile-context/search}</li>
 *   <li>画像 KPI（覆盖客户/标签总数）→ {@code /api/customer/internal/profile-metrics}</li>
 * </ul>
 * 累计消费/真实姓名/掩码手机号等敏感字段仅以系统身份（X-Internal-Token，internal:customer-directory）取回；
 * 客户域不可用时一律降级（null / 空列表 / 零值 Map），不阻断画像页主流程，前端据空态诚实引导。
 */
@Component
public class CustomerProfileClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileClient.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${customer.service.url:http://127.0.0.1:8082}")
    private String customerBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public CustomerProfileClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 单客户画像上下文；客户域不可用/不存在均返回 null（调用方转 4xx/空态）。 */
    public Map<String, Object> fetchContext(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return null;
        }
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    customerBaseUrl + "/api/customer/internal/profile-context/" + customerId.trim(),
                    HttpMethod.GET, entity(), new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (Exception e) {
            log.warn("客户画像上下文拉取失败（降级为 null），customerId={} : {}", customerId, e.getMessage());
            return null;
        }
    }

    /** 关键词搜索候选（姓名/手机号/客户编号），失败降级空列表。 */
    public List<Map<String, Object>> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        try {
            URI uri = UriComponentsBuilder
                    .fromHttpUrl(customerBaseUrl + "/api/customer/internal/profile-context/search")
                    .queryParam("keyword", keyword.trim())
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();
            ResponseEntity<List<Map<String, Object>>> resp =
                    restTemplate.exchange(uri, HttpMethod.GET, entity(), LIST_MAP_TYPE);
            return resp.getBody() == null ? List.of() : resp.getBody();
        } catch (Exception e) {
            log.warn("客户画像候选搜索失败（降级为空），keyword={} : {}", keyword, e.getMessage());
            return List.of();
        }
    }

    /** 画像 KPI：覆盖客户数/标签总数；失败降级为双零（前端展示 0，不造趋势）。 */
    public Map<String, Object> metrics() {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    customerBaseUrl + "/api/customer/internal/profile-metrics",
                    HttpMethod.GET, entity(), new ParameterizedTypeReference<>() {});
            return resp.getBody() == null ? zeroMetrics() : resp.getBody();
        } catch (Exception e) {
            log.warn("客户画像 KPI 拉取失败（降级为双零）: {}", e.getMessage());
            return zeroMetrics();
        }
    }

    private HttpEntity<Void> entity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        return new HttpEntity<>(headers);
    }

    private Map<String, Object> zeroMetrics() {
        Map<String, Object> m = new HashMap<>();
        m.put("coveredCustomers", 0L);
        m.put("tagTotal", 0L);
        return m;
    }
}
