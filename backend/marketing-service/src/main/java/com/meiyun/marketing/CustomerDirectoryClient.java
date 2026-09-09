package com.meiyun.marketing;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 客户目录跨域校验（服务间调用，铁律：禁止直读 customer 表）。
 *
 * <p>调 customer-service {@code GET /api/customer/internal/customers/{id}}（X-Internal-Token 系统身份），
 * 回客户存在性 + 归属门店。发赠金是真实资金权益，必须硬校验：
 * <ul>
 *   <li>客户不存在（404）→ 400 中文拦截，不得凭空发赠金；</li>
 *   <li>客户域不可用 / 5xx / 超时 → 502 硬失败（合规场景不允许降级成「照发」）；</li>
 *   <li>成功 → 返回目录投影（含 storeCode）。</li>
 * </ul>
 */
@Component
public class CustomerDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerDirectoryClient.class);

    private final RestTemplate restTemplate;

    @Value("${customer.service.url:http://127.0.0.1:8082}")
    private String customerBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public CustomerDirectoryClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 客户目录投影（customerId/name/storeCode/status）。 */
    public record CustomerDirectory(String customerId, String name, String storeCode, String status) {
    }

    /**
     * 硬校验客户存在性。客户不存在 → 400；客户域不可用 → 502。
     */
    public CustomerDirectory requireCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户ID不可为空");
        }
        String id = customerId.trim();
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        try {
            CustomerDirectory d = restTemplate.exchange(
                    customerBaseUrl + "/api/customer/internal/customers/" + id,
                    HttpMethod.GET, new HttpEntity<>(headers), CustomerDirectory.class).getBody();
            if (d == null || d.customerId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "客户域返回内容异常，发赠金中止");
            }
            return d;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户不存在，不可发赠金：" + id);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("客户域校验失败，发赠金硬中止 customerId={}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "客户域暂不可用，发赠金中止，请稍后重试");
        }
    }
}
