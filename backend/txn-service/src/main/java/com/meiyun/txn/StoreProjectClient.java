package com.meiyun.txn;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * txn → store SKU 目录客户端（B49 卡7 M1 大屏品类占比）：经 X-Internal-Token 以系统身份
 * （GROUP 全域 + perms=["*"]，meiyun-security AuthInterceptor 放行 brand:view）调
 * {@code GET /api/stores/skus} 空参全量列表，构建「SKU 名称 → serviceCategory 五枚举」映射，
 * 供 order_item.item_name 精确匹配归桶，免 join 品类表。
 *
 * <p>容错口径（与 StoreCatalogClient 售卡主链路 502 不同）：大屏品类占比是读侧富化，
 * store 不可用/超时 → log.warn 并沿用上份缓存（或空映射，子项全部归「其他」），
 * 绝不让 overview 端点因品类映射失败而 5xx。60s 缓存防抖，失败同样记时间免每轮打爆 store。
 */
@Component
public class StoreProjectClient {

    private static final Logger log = LoggerFactory.getLogger(StoreProjectClient.class);
    private static final long CACHE_MS = 60_000L;

    private final RestTemplate restTemplate;

    @Value("${store.service.url:http://127.0.0.1:8085}")
    private String storeBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    private volatile Map<String, String> cache = Map.of();
    private volatile long cacheAt = 0L;

    public StoreProjectClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * SKU 名称 → serviceCategory（INJECTION/LASER/SKINCARE/BODY/EXAM，空串=未分类）映射。
     * 同名跨品牌取首命中（设计风险①如实标注）；刷新失败沿用旧缓存，首次失败返回空 Map。
     */
    public Map<String, String> skuCategoryMap() {
        long now = System.currentTimeMillis();
        if (now - cacheAt < CACHE_MS) {
            return cache;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            List<Map<String, Object>> rows = restTemplate.exchange(
                    storeBaseUrl + "/api/stores/skus", HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    }).getBody();
            Map<String, String> fresh = new HashMap<>();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Object name = row.get("name");
                    if (name == null) {
                        continue;
                    }
                    Object cat = row.get("serviceCategory");
                    fresh.putIfAbsent(String.valueOf(name), cat == null ? "" : String.valueOf(cat));
                }
            }
            cache = fresh;
            cacheAt = now;
            return fresh;
        } catch (Exception e) {
            log.warn("大屏品类映射刷新失败（沿用缓存/空映射，子项归「其他」）: {}", e.getMessage());
            cacheAt = now;
            return cache;
        }
    }
}
