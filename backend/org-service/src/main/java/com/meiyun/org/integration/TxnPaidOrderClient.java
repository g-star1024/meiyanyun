package com.meiyun.org.integration;

import com.meiyun.security.AuthInterceptor;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * org → txn 已支付单拉取客户端（T3-B2 Outbox 单向镜像引擎拉单源）。
 *
 * <p>复用 B24 既有端点 GET /api/txn/internal/paid-orders?from=&to=（InternalOrderController，
 * 权限 internal:finance-flow，系统身份 X-Internal-Token 天然放行）——txn 侧零改动、零新业务码。
 *
 * <p>红线②诚实口径：拉取失败如实抛 {@link PullFailed}（sync 端点映 502 中文），
 * 绝不软降级为空列表伪造「同步 0 条」假象。
 */
@Component
public class TxnPaidOrderClient {

    private static final Logger log = LoggerFactory.getLogger(TxnPaidOrderClient.class);

    private final RestTemplate restTemplate;

    @Value("${txn.service.url:http://127.0.0.1:8083}")
    private String txnBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public TxnPaidOrderClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 拉取 [from, to]（yyyy-MM-dd 闭区间）已收款订单投影；失败如实抛 PullFailed。 */
    public List<PaidOrderView> fetchPaidOrders(LocalDate from, LocalDate to) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        String url = txnBaseUrl + "/api/txn/internal/paid-orders?from=" + from + "&to=" + to;
        try {
            var response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), PaidOrderView[].class);
            PaidOrderView[] body = response.getBody();
            return body == null ? List.of() : Arrays.asList(body);
        } catch (Exception e) {
            log.error("txn 已支付单拉取失败 from={} to={} : {}", from, to, e.getMessage());
            throw new PullFailed("交易域已支付单拉取失败：" + e.getMessage());
        }
    }

    /** txn 侧已收款订单精简投影（照 InternalOrderController.PaidOrderView 九字段；amount 单位 Long「分」）。 */
    public record PaidOrderView(String orderNo, String customerId, String storeCode,
                                Long amount, String status, String bizKind, OffsetDateTime createdAt,
                                String sourceType, String sourceId) {
    }

    /** 拉取失败（映射 502 中文）。 */
    public static class PullFailed extends RuntimeException {
        public PullFailed(String m) {
            super(m);
        }
    }
}
