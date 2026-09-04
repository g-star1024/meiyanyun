package com.meiyun.txn;

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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工名解析：订单 consultant / 预约 doctor 存的是工号（SE007），客户视图需显中文。
 *
 * <p>通过 <b>服务间 REST 调用</b> org-service（{@code /api/org/staff/name-map}）批量解析，
 * 不再直连 staff 表——微服务按域拆库后本类零改动即可成立（一劳永逸）。
 * 解析服务不可用时降级返回空 Map（名字回退为工号/不展示），不阻断订单/预约主流程。
 */
@Component
public class TxnStaffNameResolver {

    private static final Logger log = LoggerFactory.getLogger(TxnStaffNameResolver.class);
    private static final ParameterizedTypeReference<Map<String, String>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${org.service.url:http://127.0.0.1:8086}")
    private String orgBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public TxnStaffNameResolver(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** staff_id → staff_name（批量；服务异常返回空 Map）。 */
    public Map<String, String> staffNames(Collection<String> staffIds) {
        List<String> ids = staffIds.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Collections.emptyMap();
        try {
            UriComponentsBuilder builder =
                    UriComponentsBuilder.fromHttpUrl(orgBaseUrl + "/api/org/staff/name-map");
            ids.forEach(v -> builder.queryParam("ids", v));
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            ResponseEntity<Map<String, String>> resp =
                    restTemplate.exchange(builder.toUriString(), HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);
            Map<String, String> body = resp.getBody();
            return body != null ? body : new HashMap<>();
        } catch (Exception e) {
            log.warn("员工名解析失败（降级为工号），数量={} : {}", ids.size(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 单个工号 → 中文名，查不到回退工号本身。 */
    public String nameOf(String staffId) {
        if (staffId == null || staffId.isBlank()) return null;
        return staffNames(List.of(staffId)).getOrDefault(staffId, staffId);
    }
}
