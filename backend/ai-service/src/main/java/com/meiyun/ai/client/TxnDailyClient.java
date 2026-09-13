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
import java.util.Map;

/**
 * AI 经营日报单日经营指标跨服务客户端（不直读 txn 表）：
 * 交易域 {@code /api/txn/internal/daily-metrics} 返回 Asia/Shanghai 自然日的真实聚合
 * （已收款营收/到店登记/首单新客/已完成退款与风控异常），仅以系统身份（X-Internal-Token，
 * internal:finance-flow）取回；交易域不可用时返回 {@code null}，日报页引导空态/生成失败而不是造数。
 */
@Component
public class TxnDailyClient {

    private static final Logger log = LoggerFactory.getLogger(TxnDailyClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${txn.service.url:http://127.0.0.1:8083}")
    private String txnBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public TxnDailyClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 单日经营指标；storeCode 可空（全集团）；任何失败降级 null（调用方不造数）。 */
    public Map<String, Object> dailyMetrics(String date, String storeCode) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(txnBaseUrl + "/api/txn/internal/daily-metrics");
            if (date != null && !date.isBlank()) {
                b.queryParam("date", date.trim());
            }
            if (storeCode != null && !storeCode.isBlank()) {
                b.queryParam("storeCode", storeCode.trim());
            }
            URI uri = b.encode(StandardCharsets.UTF_8).build().toUri();
            ResponseEntity<Map<String, Object>> resp =
                    restTemplate.exchange(uri, HttpMethod.GET, entity(), MAP_TYPE);
            return resp.getBody();
        } catch (Exception e) {
            log.warn("经营日报指标拉取失败（降级为空）: {}", e.getMessage());
            return null;
        }
    }

    private HttpEntity<Void> entity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        return new HttpEntity<>(headers);
    }
}
