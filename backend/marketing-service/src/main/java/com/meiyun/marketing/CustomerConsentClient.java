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

import java.time.OffsetDateTime;

/**
 * 客户同意状态跨域校验（服务间调用，铁律：禁止直读 customer 表）。
 *
 * <p>调 customer-service {@code GET /api/customer/internal/consent/{customerId}}（X-Internal-Token 系统身份），
 * 回客户同意状态（consent_version/consent_at/consent_withdrawn_at）。
 *
 * <p>P5-B58 卡3 撤回联动（设计文档 §3.3）：marketing-service 推送前调本端点查询客户同意状态，
 * 已撤回（consent_withdrawn_at 非 NULL 且 > consent_at）→ 跳过推送（合规拦截，不落 push_record）。
 *
 * <p>失败降级策略（合规场景不允许"照推"）：
 * <ul>
 *   <li>客户不存在（404）→ 400 中文拦截，不得向不存在的客户推送</li>
 *   <li>客户域不可用 / 5xx / 超时 → 502 硬失败（合规场景不允许降级成「照推」）</li>
 *   <li>成功 → 返回同意状态投影</li>
 * </ul>
 *
 * <p>与 {@link CustomerDirectoryClient} 同款范式（RestTemplate + X-Internal-Token）。
 */
@Component
public class CustomerConsentClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerConsentClient.class);

    private final RestTemplate restTemplate;

    @Value("${customer.service.url:http://127.0.0.1:8082}")
    private String customerBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public CustomerConsentClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 同意状态投影（与 customer-service ConsentService.ConsentStatus 同款字段名）。 */
    public record ConsentStatus(String customerId, Integer consentVersion,
                                OffsetDateTime consentAt, OffsetDateTime consentWithdrawnAt) {
    }

    /**
     * 硬校验客户同意状态：客户不存在 → 400；客户域不可用 → 502；已撤回 → 返回状态由调用方跳过推送。
     */
    public ConsentStatus requireConsent(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户ID不可为空");
        }
        String id = customerId.trim();
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        try {
            ConsentStatus s = restTemplate.exchange(
                    customerBaseUrl + "/api/customer/internal/consent/" + id,
                    HttpMethod.GET, new HttpEntity<>(headers), ConsentStatus.class).getBody();
            if (s == null || s.customerId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "客户域返回内容异常，推送中止");
            }
            return s;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户不存在，不可推送：" + id);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("客户同意状态校验失败，推送硬中止 customerId={}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "客户域暂不可用，推送中止，请稍后重试");
        }
    }

    /**
     * 判断是否已撤回同意（consent_withdrawn_at 非 NULL 且 > consent_at）。
     * 未授权（version=0）或未撤回 → false；已撤回 → true。
     */
    public static boolean isWithdrawn(ConsentStatus s) {
        if (s == null) return false;
        if (s.consentWithdrawnAt() == null) return false;
        if (s.consentAt() == null) return true;  // 无 consent_at 但有 withdrawn_at 视为已撤回（异常态兜底）
        return s.consentWithdrawnAt().isAfter(s.consentAt());
    }

    /**
     * 判断是否已授权同意（consent_version ≥ 1 且未撤回）。
     * 未授权（version=0）→ false；已授权且未撤回 → true。
     */
    public static boolean isGranted(ConsentStatus s) {
        if (s == null) return false;
        if (s.consentVersion() == null || s.consentVersion() == 0) return false;
        return !isWithdrawn(s);
    }
}
