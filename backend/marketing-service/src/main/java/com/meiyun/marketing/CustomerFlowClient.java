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

import java.util.List;

/**
 * customer 域生日候选只读投影客户端（P5-B90 Flow BIRTHDAY trigger，服务间调用，
 * 铁律：禁止 JdbcTemplate 直读别域表）。
 *
 * <p>调 customer {@code GET /api/customer/internal/birthday-on?month=&day=}（X-Internal-Token 系统身份），
 * 回当日生日且活跃（未合并/未匿名化）客户。连接/超时/4xx/5xx 统一包成
 * {@link CustomerServiceUnavailableException}，由 MarketingFlowService 决定
 * 「该规则本轮软降级跳过、下轮自愈」（沿 TxnInternalClient 软降级范式）。
 */
@Component
public class CustomerFlowClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerFlowClient.class);
    private static final ParameterizedTypeReference<List<BirthdayCandidate>> BIRTHDAY_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${customer.service.url:http://127.0.0.1:8082}")
    private String customerBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public CustomerFlowClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 生日候选精简视图（与 customer InternalProfileController.BirthdayDTO 四字段对齐）。 */
    public record BirthdayCandidate(String customerId, String name, String storeCode, String level) {}

    /** 客户目录精简视图（与 customer 内部端点 customers/{id} 投影对齐，供姓名/门店解析）。 */
    public record CustomerBrief(String customerId, String name, String storeCode, String status) {}

    /**
     * 软降级语义的客户目录查询（与 CustomerDirectoryClient.requireCustomer 同端点、不同错误语义）：
     * 客户不存在（404）→ 返回 null（由调用方落 SKIPPED / 400）；域不可用 → CustomerServiceUnavailableException。
     */
    public CustomerBrief fetchCustomer(String customerId) {
        String url = customerBaseUrl + "/api/customer/internal/customers/" + customerId;
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        try {
            return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), CustomerBrief.class)
                    .getBody();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return null;
        } catch (Exception e) {
            log.warn("拉取 customer 目录投影失败 customerId={}：{}", customerId, e.getMessage());
            throw new CustomerServiceUnavailableException(
                    "customer-service 目录投影暂不可用：" + e.getMessage(), e);
        }
    }

    /**
     * 拉取指定月/日生日候选（业务时区 +8 今日由调用方解析）。
     *
     * @throws CustomerServiceUnavailableException customer 域不可用（连接/超时/HTTP 故障）
     */
    public List<BirthdayCandidate> fetchBirthdayOn(int month, int day) {
        String url = customerBaseUrl + "/api/customer/internal/birthday-on?month=" + month + "&day=" + day;
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        try {
            ResponseEntity<List<BirthdayCandidate>> resp =
                    restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), BIRTHDAY_TYPE);
            List<BirthdayCandidate> body = resp.getBody();
            return body == null ? List.of() : body;
        } catch (Exception e) {
            log.warn("拉取 customer 生日候选失败，本轮 BIRTHDAY 规则跳过：{}", e.getMessage());
            throw new CustomerServiceUnavailableException(
                    "customer-service 生日候选投影暂不可用：" + e.getMessage(), e);
        }
    }
}
