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

import java.time.OffsetDateTime;
import java.util.List;

/**
 * txn-service 已收款订单只读投影客户端（服务间调用，铁律：禁止 JdbcTemplate 直读别域表）。
 *
 * <p>调 txn {@code GET /api/txn/internal/paid-orders?from=&to=}（X-Internal-Token 系统身份），
 * 与 customer 域 AutoPointsService 共用同一事件源（域①-258），日粒度闭区间窗口。
 * 连接/超时/4xx/5xx 统一包成 {@link TxnServiceUnavailableException}，
 * 由 {@link AutoGrantService} 决定「本轮不推进游标、下轮自愈」。
 *
 * <p>视图字段必须与 txn {@code InternalOrderController.PaidOrderView} 严格对齐：
 * 金额单位 Long「分」，bizKind="CARD_SALE" 标识售卡/充值单（不属消费，不参与满赠）。
 */
@Component
public class TxnInternalClient {

    private static final Logger log = LoggerFactory.getLogger(TxnInternalClient.class);
    private static final ParameterizedTypeReference<List<PaidOrder>> PAID_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${txn.service.url:http://localhost:8083}")
    private String txnBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public TxnInternalClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 已收款订单精简视图（与 txn InternalOrderController.PaidOrderView 七字段对齐）。 */
    public record PaidOrder(String orderNo, String customerId, String storeCode,
                            Long amount, String status, String bizKind, OffsetDateTime createdAt) {}

    /**
     * 拉取 [from, to]（yyyy-MM-dd，按 created_at 闭区间）已收款订单。
     *
     * @throws TxnServiceUnavailableException txn 域不可用（连接/超时/HTTP 故障）
     */
    public List<PaidOrder> fetchPaidOrders(String from, String to) {
        String url = txnBaseUrl + "/api/txn/internal/paid-orders?from=" + from + "&to=" + to;
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        try {
            ResponseEntity<List<PaidOrder>> resp =
                    restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), PAID_TYPE);
            List<PaidOrder> body = resp.getBody();
            return body == null ? List.of() : body;
        } catch (Exception e) {
            log.warn("拉取 txn 已收款订单失败，本轮自动发赠金跳过：{}", e.getMessage());
            throw new TxnServiceUnavailableException(
                    "txn-service 已收款订单投影暂不可用：" + e.getMessage(), e);
        }
    }
}
