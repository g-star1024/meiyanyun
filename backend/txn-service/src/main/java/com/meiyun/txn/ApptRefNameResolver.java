package com.meiyun.txn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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
 * 预约列表富化用的引用名解析器（服务间 REST 调用，不直读别域表）：
 * <ul>
 *   <li>员工工号（doctor）→ org-service {@code /api/org/staff/name-map}</li>
 *   <li>门店编码（store_code）→ store-service {@code /api/stores/name-map}</li>
 *   <li>客户号（customer_id）→ customer-service {@code /api/customer/name-map}</li>
 * </ul>
 * 任一被调方不可用均降级为空 Map（名字回退为编码/不展示），不阻断预约看板主流程。
 */
@Component
public class ApptRefNameResolver {

    private static final Logger log = LoggerFactory.getLogger(ApptRefNameResolver.class);
    private static final ParameterizedTypeReference<Map<String, String>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${org.service.url:http://127.0.0.1:8086}")
    private String orgBaseUrl;
    @Value("${store.service.url:http://127.0.0.1:8085}")
    private String storeBaseUrl;
    @Value("${customer.service.url:http://127.0.0.1:8082}")
    private String customerBaseUrl;

    public ApptRefNameResolver(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 员工工号 → 姓名。 */
    public Map<String, String> staffNames(Collection<String> ids) {
        return fetch(orgBaseUrl + "/api/org/staff/name-map", "ids", ids, "员工名");
    }

    /** 门店编码 → 门店名。 */
    public Map<String, String> storeNames(Collection<String> codes) {
        return fetch(storeBaseUrl + "/api/stores/name-map", "codes", codes, "门店名");
    }

    /** 客户号 → 客户名。 */
    public Map<String, String> customerNames(Collection<String> ids) {
        return fetch(customerBaseUrl + "/api/customer/name-map", "ids", ids, "客户名");
    }

    private Map<String, String> fetch(String baseUrl, String param, Collection<String> values, String label) {
        List<String> vals = values.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (vals.isEmpty()) return Collections.emptyMap();
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl);
            vals.forEach(v -> builder.queryParam(param, v));
            ResponseEntity<Map<String, String>> resp =
                    restTemplate.exchange(builder.toUriString(), HttpMethod.GET, null, MAP_TYPE);
            Map<String, String> body = resp.getBody();
            return body != null ? body : new HashMap<>();
        } catch (Exception e) {
            log.warn("{}解析失败（降级为编码），数量={} : {}", label, vals.size(), e.getMessage());
            return Collections.emptyMap();
        }
    }
}
