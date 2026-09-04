package com.meiyun.marketing;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店名解析（服务间调用，铁律：禁止 JdbcTemplate 直读别域表）。
 *
 * <p>调 store-service {@code GET /api/stores/name-map?codes=SST01,SST02}
 * 返回 store_code → store_name；被调方不可用时降级返回空 Map（不阻断主流程），
 * 调用方按「解析不到则回显编码」兜底。
 */
@Component
public class StoreNameResolver {

    private static final Logger log = LoggerFactory.getLogger(StoreNameResolver.class);

    private final RestTemplate restTemplate;

    @Value("${store.service.url:http://127.0.0.1:8085}")
    private String storeBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public StoreNameResolver(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 批量解析门店编码 → 中文名；异常/空入参返回空 Map。 */
    public Map<String, String> resolveNames(List<String> codes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return out;
        }
        List<String> distinct = codes.stream()
                .filter(c -> c != null && !c.isBlank()).map(String::trim).distinct().toList();
        if (distinct.isEmpty()) {
            return out;
        }
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(storeBaseUrl + "/api/stores/name-map")
                    .queryParam("codes", String.join(",", distinct))
                    .toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            ResponseEntity<Map<String, String>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<String, String>>() {});
            if (resp.getBody() != null) {
                out.putAll(resp.getBody());
            }
        } catch (Exception e) {
            log.warn("门店名解析失败，降级回显编码：{}", e.getMessage());
        }
        return out;
    }
}
