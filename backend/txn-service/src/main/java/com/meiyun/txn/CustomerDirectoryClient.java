package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

/**
 * txn → customer 客户目录反查客户端（M2-09 到店核销登记锚定客户）：交易域不直读 customer 表，
 * 一律经 customer 内部端点以系统身份（X-Internal-Token，perms=["*"]）调用。
 *
 * <p>按手机号反查命中本店客户/公海客户；customer 返回 404 表示无此客户（合法结果，非错误），
 * 由 {@link #findByPhone} 归一为 empty，调用方据此以「快照散客」建登记单；
 * 其余 4xx 透传中文，网络异常 / 5xx → 502 中文（登记事务回滚，不容忍 customer 不可用时静默散客化）。
 */
@Component
public class CustomerDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerDirectoryClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${customer.service.url:http://127.0.0.1:8082}")
    private String customerBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public CustomerDirectoryClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 客户目录投影（customerId/姓名/归属门店/状态）。 */
    public record Directory(String customerId, String name, String storeCode, String status) {}

    /**
     * 按手机号反查客户：本店客户优先、公海客户兜底（命中规则由 customer 侧裁决）。
     * 手机号未命中（404）→ Optional.empty；其余失败抛 4xx 中文 / 502。
     */
    public Optional<Directory> findByPhone(String phone, String storeCode) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            String url = UriComponentsBuilder.fromHttpUrl(customerBaseUrl + "/api/customer/internal/customers/by-phone")
                    .queryParam("phone", phone)
                    .queryParam("storeCode", storeCode == null ? "" : storeCode)
                    .toUriString();
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            if (body == null || body.get("customerId") == null) {
                return Optional.empty();
            }
            return Optional.of(new Directory(
                    String.valueOf(body.get("customerId")),
                    body.get("name") == null ? "" : String.valueOf(body.get("name")),
                    body.get("storeCode") == null ? "" : String.valueOf(body.get("storeCode")),
                    body.get("status") == null ? "" : String.valueOf(body.get("status"))));
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                return Optional.empty();
            }
            if (status >= 400 && status < 500) {
                log.info("手机号反查客户被 customer 拒绝 status={} body={}", status, e.getResponseBodyAsString());
                throw new ResponseStatusException(HttpStatus.valueOf(status),
                        extractMessage(e.getResponseBodyAsString()));
            }
            log.error("手机号反查客户 customer 服务端错误 status={}", status);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "到店登记失败：客户服务暂不可用，请稍后重试（本次未保存登记单）");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("手机号反查客户 customer 调用异常: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "到店登记失败：无法连接客户服务，请稍后重试（本次未保存登记单）");
        }
    }

    private static String extractMessage(String body) {
        if (body != null && body.contains("\"message\"")) {
            try {
                String m = MAPPER.readTree(body).path("message").asText(null);
                if (m != null && !m.isBlank()) return m;
            } catch (Exception ignored) {
                // 落到兜底文案
            }
        }
        return "客户服务拒绝了本次操作，请稍后重试";
    }
}
