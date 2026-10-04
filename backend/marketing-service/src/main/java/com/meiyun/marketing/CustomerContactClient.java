package com.meiyun.marketing;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 客户明文联系方式跨域查询（服务间调用，铁律：禁止直读 customer 表）。
 *
 * <p>调 customer-service {@code GET /api/customer/internal/contact/{customerId}}（X-Internal-Token
 * 系统身份），回明文手机号供短信外发取收件号码（棒⑧卡2）。
 *
 * <p>与 {@link CustomerConsentClient} 的合规硬失败不同：外发腿已脱离推送主事务
 * （AFTER_COMMIT），本客户端<strong>软降级</strong>——任何异常（客户域不可用/客户不存在/超时）
 * 一律 log.warn 并返回 null，由 PushDispatchService 落 SKIPPED 诚实注记，不抛错、不伪造成功。
 */
@Component
public class CustomerContactClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerContactClient.class);

    private final RestTemplate restTemplate;

    @Value("${customer.service.url:http://127.0.0.1:8082}")
    private String customerBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public CustomerContactClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 联系方式投影（与 customer-service InternalContactController.ContactView 同款字段名）。 */
    public record ContactView(String customerId, String phone) {
    }

    /** 取客户明文手机号；任何失败返回 null（调用方落 SKIPPED）。 */
    public String resolvePhone(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return null;
        }
        String id = customerId.trim();
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        try {
            ContactView v = restTemplate.exchange(
                    customerBaseUrl + "/api/customer/internal/contact/" + id,
                    HttpMethod.GET, new HttpEntity<>(headers), ContactView.class).getBody();
            return v == null ? null : v.phone();
        } catch (Exception e) {
            log.warn("客户手机号查询失败（外发腿软降级）customerId={}: {}", id, e.getMessage());
            return null;
        }
    }
}
