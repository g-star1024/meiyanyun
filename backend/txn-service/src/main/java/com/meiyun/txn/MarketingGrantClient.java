package com.meiyun.txn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 营销赠金域客户端（收银台赠金抵扣）。禁 JdbcTemplate 跨域直读，一律走 HTTP 内部端点。
 *
 * <p>错误口径与 {@link CustomerCardClient} 一致：4xx 原样透传营销域中文文案（如余额不足），
 * 5xx / 网络异常统一 502 并明示「本笔操作已回滚，未扣款未记账」，避免收银员误判已收款。
 */
@Component
public class MarketingGrantClient {

    private static final Logger log = LoggerFactory.getLogger(MarketingGrantClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${marketing.service.url:http://127.0.0.1:8088}")
    private String marketingBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public MarketingGrantClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 赠金抵扣（幂等键=订单号，同单重放不双扣）。余额不足由营销域抛 422 中文，本方法原样透传。
     */
    public void deduct(String customerId, long amountFen, String orderNo, String storeCode, String operator) {
        Map<String, Object> body = Map.of(
                "customerId", nz(customerId),
                "amountFen", amountFen,
                "bizRef", nz(orderNo),
                "storeCode", nz(storeCode),
                "operator", nz(operator));
        post("/api/marketing/internal/grants/deduct", body, "赠金抵扣");
    }

    /**
     * 退款终审赠金回加（B39，幂等键=退款单号，同终审重放不双加）。
     * amountFen 为本次退款按「赠金→卡本金→法币」级联拆出的赠金段；可回额不足由营销域 422 中文透传，
     * 5xx/网络异常 502——调用方（退款终审事务）据此整体回滚，保证「未回加成功就不置 REFUNDED」。
     */
    public void refund(String customerId, long amountFen, String orderNo, String refundNo,
                       String storeCode, String operator) {
        Map<String, Object> body = Map.of(
                "customerId", nz(customerId),
                "amountFen", amountFen,
                "orderNo", nz(orderNo),
                "refundNo", nz(refundNo),
                "storeCode", nz(storeCode),
                "operator", nz(operator));
        post("/api/marketing/internal/grants/refund", body, "赠金退款回加");
    }

    /** 可用赠金余额（分）；营销域不可用时抛 502，由调用方决定是否降级。 */
    public long balance(String customerId) {
        String url = UriComponentsBuilder.fromHttpUrl(marketingBaseUrl + "/api/marketing/internal/grants/balance")
                .queryParam("customerId", nz(customerId))
                .toUriString();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            Map<?, ?> resp = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class).getBody();
            Object v = resp == null ? null : resp.get("balanceFen");
            return v instanceof Number n ? n.longValue() : 0L;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String msg = extractMessage(e.getResponseBodyAsString());
            if (status >= 400 && status < 500) {
                log.info("查询赠金余额被拒绝 customerId={} status={} msg={}", customerId, status, msg);
                throw new ResponseStatusException(HttpStatus.valueOf(status), msg);
            }
            log.error("查询赠金余额失败 customerId={} status={}", customerId, status, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "查询赠金余额失败：营销服务暂不可用，请稍后重试");
        } catch (Exception e) {
            log.error("查询赠金余额异常 customerId={}", customerId, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "查询赠金余额失败：无法连接营销服务，请稍后重试");
        }
    }

    private void post(String path, Map<String, Object> body, String label) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            restTemplate.postForEntity(marketingBaseUrl + path, new HttpEntity<>(body, headers), Map.class);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String msg = extractMessage(e.getResponseBodyAsString());
            if (status >= 400 && status < 500) {
                log.info("{}被营销服务拒绝 status={} msg={} body={}", label, status, msg, body);
                throw new ResponseStatusException(HttpStatus.valueOf(status), msg);
            }
            log.error("{}失败 status={} body={}", label, status, body, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    label + "失败：营销服务暂不可用，请稍后重试（本笔操作已回滚，未扣款未记账）");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("{}异常 body={}", label, body, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    label + "失败：无法连接营销服务，请稍后重试（本笔操作已回滚，未扣款未记账）");
        }
    }

    private static String extractMessage(String body) {
        try {
            JsonNode n = MAPPER.readTree(body);
            String msg = n.path("message").asText(null);
            if (msg != null && !msg.isBlank()) {
                return msg;
            }
        } catch (Exception ignore) {
            // 非 JSON 响应体，回落通用中文
        }
        return "营销服务拒绝了本次操作，请核对赠金余额与有效期后重试";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
