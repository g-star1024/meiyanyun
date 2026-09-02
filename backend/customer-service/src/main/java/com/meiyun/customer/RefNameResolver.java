package com.meiyun.customer;

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
 * 名字解析器：把客户上的归属员工工号 / 门店编码解析为中文名，供读模型 DTO 冗余展示。
 *
 * <p>背景：customer 表只存 owner_staff_id / store_code（逻辑外键），界面必须显中文（铁律：零技术码外露）。
 * 本类通过 <b>服务间 REST 调用</b> 解析：员工名走 org-service（{@code /api/org/staff/name-map}）、
 * 门店名走 store-service（{@code /api/stores/name-map}），不再直连 staff/store 表——
 * 微服务按域拆库后本类零改动即可成立（一劳永逸）。
 *
 * <p>降级策略：解析服务不可用时仅记录日志、返回空 Map（名字字段回退为不展示），不阻断客户列表/详情主流程。
 */
@Component
public class RefNameResolver {

    private static final Logger log = LoggerFactory.getLogger(RefNameResolver.class);
    private static final ParameterizedTypeReference<Map<String, String>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${org.service.url:http://127.0.0.1:8086}")
    private String orgBaseUrl;

    @Value("${store.service.url:http://127.0.0.1:8085}")
    private String storeBaseUrl;

    public RefNameResolver(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** staff_id → staff_name（批量，缺失不留 key；服务异常返回空 Map）。 */
    public Map<String, String> staffNames(Collection<String> staffIds) {
        List<String> ids = staffIds.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Collections.emptyMap();
        return fetchNameMap(orgBaseUrl + "/api/org/staff/name-map", "ids", ids, "员工名");
    }

    /** store_code → store_name（批量，缺失不留 key；服务异常返回空 Map）。 */
    public Map<String, String> storeNames(Collection<String> storeCodes) {
        List<String> codes = storeCodes.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (codes.isEmpty()) return Collections.emptyMap();
        return fetchNameMap(storeBaseUrl + "/api/stores/name-map", "codes", codes, "门店名");
    }

    /** 通用批量名解析：GET base?param=v1&amp;param=v2 → Map&lt;code,name&gt;，失败降级空 Map。 */
    private Map<String, String> fetchNameMap(String baseUrl, String param, List<String> values, String label) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl);
            values.forEach(v -> builder.queryParam(param, v));
            ResponseEntity<Map<String, String>> resp =
                    restTemplate.exchange(builder.toUriString(), HttpMethod.GET, null, MAP_TYPE);
            Map<String, String> body = resp.getBody();
            return body != null ? body : new HashMap<>();
        } catch (Exception e) {
            log.warn("{}解析失败（降级为不展示），数量={} : {}", label, values.size(), e.getMessage());
            return Collections.emptyMap();
        }
    }
}
