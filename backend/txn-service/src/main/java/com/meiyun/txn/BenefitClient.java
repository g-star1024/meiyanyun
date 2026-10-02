package com.meiyun.txn;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * txn → customer 免费护理核销客户端（棒⑤卡3 L161，开单免单联动）：交易域不直读 customer 权益表，
 * 一律经 customer 内部端点以系统身份（X-Internal-Token，perms=["*"]）调用，拆库后零改动。
 *
 * <p>调用时序「先扣后生」：开单事务在本地全部校验/落库完成后、提交前调 {@link #consumeForOrder}；
 * customer 侧以 order_no 为订单级幂等锚（同 orderNo 重放零副作用），行锁扣次。
 *
 * <p>错误口径（与 {@link MemberDiscountClient} 一致，fail-closed 不降级）：customer 返回 4xx
 * → 透传其状态码与中文 message（400 参数 / 404 客户 / 422 次数不足或无权益，开单事务整笔回滚）；
 * 网络异常 / 5xx → 502 中文（同样回滚，杜绝「扣次失败却照常落单」）。
 */
@Component
public class BenefitClient {

    private static final Logger log = LoggerFactory.getLogger(BenefitClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${customer.service.url:http://127.0.0.1:8082}")
    private String customerBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public BenefitClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 订单级免费护理扣次：POST /api/customer/internal/benefits/order-consume。
     * projectNames 为空直接返回（零远程调用，普通单零开销）；任何 4xx/5xx/网络异常上抛，
     * 由调用方 @Transactional 整笔回滚（不落单）。
     */
    public void consumeForOrder(String customerId, String orderNo, List<String> projectNames) {
        if (projectNames == null || projectNames.isEmpty()) {
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("orderNo", orderNo);
        body.put("projectNames", projectNames);
        try {
            restTemplate.postForEntity(customerBaseUrl + "/api/customer/internal/benefits/order-consume",
                    new HttpEntity<>(body, headers), String.class);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status >= 400 && status < 500) {
                log.info("免费护理核销被 customer 拒绝 status={} body={}", status, e.getResponseBodyAsString());
                throw new ResponseStatusException(HttpStatus.valueOf(status),
                        extractMessage(e.getResponseBodyAsString()));
            }
            log.error("免费护理核销 customer 服务端错误 status={}", status);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "免费护理核销失败：客户服务暂不可用，请稍后重试（本笔操作已回滚，未生成订单）");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("免费护理核销 customer 调用异常: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "免费护理核销失败：无法连接客户服务，请稍后重试（本笔操作已回滚，未生成订单）");
        }
    }

    /** 从 customer 错误体 {"code":"...","message":"中文"} 提取中文原因；解析失败回落通用文案。 */
    private static String extractMessage(String body) {
        if (body != null && body.contains("\"message\"")) {
            try {
                String m = MAPPER.readTree(body).path("message").asText(null);
                if (m != null && !m.isBlank()) return m;
            } catch (Exception ignored) {
                // 落到兜底文案
            }
        }
        return "客户服务拒绝了本次开单，请稍后重试";
    }
}
