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
 * 返回 store_code → store_name。两种故障语义严格分层：
 * <ul>
 *   <li><b>远程故障</b>（连不上/超时/4xx/5xx）：{@link #resolveNamesRequired} 抛
 *       {@link StoreServiceUnavailableException}，写链路据此返 503；{@link #resolveNames}
 *       仍降级返回空 Map，仅供「回显编码」「不可用即放行」的非阻断路径使用；</li>
 *   <li><b>门店真不存在</b>：接口 200 但返回 Map 中缺码，属业务语义（400），不是故障。</li>
 * </ul>
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

    /**
     * 宽松版批量解析（仅用于展示回显、不可用即放行等非阻断路径）：
     * 远程故障（含连不上/超时/4xx/5xx）降级返回空 Map，调用方按「解析不到则回显编码」兜底；
     * 注意：空 Map 无法区分「故障」与「全不存在」，写链路校验必须改用
     * {@link #resolveNamesRequired}。空入参返回空 Map。
     */
    public Map<String, String> resolveNames(List<String> codes) {
        try {
            return fetchNames(codes);
        } catch (StoreServiceUnavailableException e) {
            log.warn("门店名解析失败，降级回显编码：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 严格版批量解析（写链路存在性校验专用）：远程故障抛
     * {@link StoreServiceUnavailableException}，由调用方转 503；
     * 接口 200 但 Map 中缺码即「门店真不存在」，由调用方判 400。空入参返回空 Map。
     */
    public Map<String, String> resolveNamesRequired(List<String> codes) {
        return fetchNames(codes);
    }

    /** 远程取数唯一出口：传输/HTTP 故障统一包成 StoreServiceUnavailableException。 */
    private Map<String, String> fetchNames(List<String> codes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return out;
        }
        List<String> distinct = codes.stream()
                .filter(c -> c != null && !c.isBlank()).map(String::trim).distinct().toList();
        if (distinct.isEmpty()) {
            return out;
        }
        String url = UriComponentsBuilder
                .fromHttpUrl(storeBaseUrl + "/api/stores/name-map")
                .queryParam("codes", String.join(",", distinct))
                .toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        try {
            ResponseEntity<Map<String, String>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<String, String>>() {});
            if (resp.getBody() != null) {
                out.putAll(resp.getBody());
            }
            return out;
        } catch (Exception e) {
            throw new StoreServiceUnavailableException(
                    "store-service 门店主数据暂不可用：" + e.getMessage(), e);
        }
    }

    /**
     * 解析任意一家可用门店（集团/大区账号无所属门店时兜底）。
     * 调 store-service {@code GET /api/stores/internal/first}，返回 {code,name}；
     * 失败或无门店返回 null（调用方降级回显固定编码）。
     */
    public Map<String, String> resolveFirstStore() {
        try {
            String url = storeBaseUrl + "/api/stores/internal/first";
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            ResponseEntity<Map<String, String>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<String, String>>() {});
            Map<String, String> body = resp.getBody();
            if (body != null && body.get("code") != null && !body.get("code").isBlank()) {
                return body;
            }
        } catch (Exception e) {
            log.warn("兜底门店解析失败，降级回显固定编码：{}", e.getMessage());
        }
        return null;
    }
}
