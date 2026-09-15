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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * txn → store 服务间客户端（P5-B51 卡4 调度中心 DEVICE 接真）：取某门店 NORMAL 态设备作为
 * DEVICE 调度资源。
 *
 * <p>交易域不直读 equipment 表，设备建档/校准/维保/停用语义归 store 域。读侧聚合为旁路动作：
 * store 不可用 / 超时 / 出错一律软降级为空列表（log.warn，设备 tab 呈现空态，绝不拖垮医生/
 * 治疗室资源与待派单查询）；派单写路径若选不到设备则按资源不存在 404 处理，不会静默派错。
 */
@Component
public class StoreEquipmentClient {

    private static final Logger log = LoggerFactory.getLogger(StoreEquipmentClient.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${store.service.url:http://127.0.0.1:8085}")
    private String storeBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public StoreEquipmentClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 取 NORMAL 态设备：GET /api/stores/internal/equipments?storeCode=…（store 侧已过滤 NORMAL）。
     * 返回行含 id/assetNo/name/category/location/status；失败软降级为空列表。
     */
    public List<Map<String, Object>> listNormalEquipments(String storeCode) {
        String sc = storeCode == null ? "" : storeCode.trim();
        if (sc.isEmpty()) return Collections.emptyList();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            String url = UriComponentsBuilder.fromHttpUrl(storeBaseUrl + "/api/stores/internal/equipments")
                    .queryParam("storeCode", URLEncoder.encode(sc, StandardCharsets.UTF_8))
                    .toUriString();
            ResponseEntity<List<Map<String, Object>>> resp =
                    restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), LIST_TYPE);
            List<Map<String, Object>> body = resp.getBody();
            return body != null ? body : Collections.emptyList();
        } catch (Exception e) {
            log.warn("调度取设备失败（软降级为空）store={}: {}", sc, e.getMessage());
            return Collections.emptyList();
        }
    }

}
