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

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * txn → customer 会员等级折扣客户端（B62 卡2，开单计价取折扣率）：交易域不直读 customer/member_level 表，
 * 一律经 customer 内部端点以系统身份（X-Internal-Token，perms=["*"]）调用，拆库后零改动。
 *
 * <p>错误口径（与 {@link CustomerCardClient} 一致，fail-closed 不降级）：customer 返回 4xx
 * → 透传其状态码与中文 message（开单事务回滚）；网络异常 / 5xx → 502 中文（同样回滚，
 * 杜绝「取不到折扣却按原价/无折扣成交」）。客户不存在/等级缺失由 customer 侧兜底 普通/NORMAL/1.00，
 * 本端不做静默兜底（只在响应体异常缺字段时回 1.00，属数据契约防御）。
 */
@Component
public class MemberDiscountClient {

    private static final Logger log = LoggerFactory.getLogger(MemberDiscountClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${customer.service.url:http://127.0.0.1:8082}")
    private String customerBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public MemberDiscountClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 取单客户会员折扣：GET /api/customer/internal/level-discount?customerIds=xxx。
     * 返回 中文等级/英文 tier/折扣率（1.00=无折扣）；开单计价前调用一次，结果在本单计价内复用。
     */
    public MemberDiscount getForCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "开单失败：缺少客户信息，无法判定会员折扣");
        }
        List<Map<String, Object>> body = fetchBatch(List.of(customerId.trim()));
        if (body.isEmpty()) {
            log.warn("会员折扣响应为空，按无折扣处理 customerId={}", customerId);
            return MemberDiscount.none(customerId.trim());
        }
        Map<String, Object> row = body.get(0);
        String id = String.valueOf(row.getOrDefault("customerId", customerId.trim()));
        String level = textOr(row.get("level"), "普通");
        String tier = textOr(row.get("tier"), "NORMAL");
        BigDecimal discount = decimalOr(row.get("discount"), BigDecimal.ONE.setScale(2));
        return new MemberDiscount(id, level, tier, discount);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchBatch(List<String> customerIds) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            String qs = customerIds.stream()
                    .map(s -> "customerIds=" + URLEncoder.encode(s, StandardCharsets.UTF_8))
                    .collect(java.util.stream.Collectors.joining("&"));
            var resp = restTemplate.exchange(
                    customerBaseUrl + "/api/customer/internal/level-discount?" + qs,
                    HttpMethod.GET, new HttpEntity<>(headers), List.class);
            List<Map<String, Object>> body = resp.getBody();
            return body == null ? List.of() : body;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status >= 400 && status < 500) {
                log.info("会员折扣查询被 customer 拒绝 status={} body={}", status, e.getResponseBodyAsString());
                throw new ResponseStatusException(HttpStatus.valueOf(status),
                        extractMessage(e.getResponseBodyAsString()));
            }
            log.error("会员折扣查询 customer 服务端错误 status={}", status);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "开单计价失败：客户服务暂不可用，请稍后重试（本笔操作已回滚，未生成订单）");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("会员折扣查询 customer 调用异常: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "开单计价失败：无法连接客户服务，请稍后重试（本笔操作已回滚，未生成订单）");
        }
    }

    private static String textOr(Object v, String fallback) {
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static BigDecimal decimalOr(Object v, BigDecimal fallback) {
        if (v == null) return fallback;
        try {
            if (v instanceof BigDecimal bd) return bd;
            if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue()).setScale(2, java.math.RoundingMode.HALF_UP);
            return new BigDecimal(String.valueOf(v).trim()).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (Exception e) {
            return fallback;
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

    /**
     * 会员折扣领域记录：customerId + 中文等级 + 英文 tier + 折扣率（1.00=无折扣）。
     */
    public record MemberDiscount(String customerId, String level, String tier, BigDecimal discount) {

        public boolean hasDiscount() {
            return discount != null && discount.compareTo(BigDecimal.ONE) < 0;
        }

        static MemberDiscount none(String customerId) {
            return new MemberDiscount(customerId, "普通", "NORMAL", BigDecimal.ONE.setScale(2));
        }
    }

    /**
     * 会员折扣计价（B62 卡2 唯一口径，三个开单入口共用）：以前端提交单价为折前基准，
     * 逐行「折后单价 = round(原价 × 折扣, HALF_UP, 分)」，行小计=折后单价×qty；
     * 整单原价合计=Σ原价×qty，折后合计=Σ行小计，优惠=原价−折后。
     * 折扣率为 null / ≥1（普通 1.00）时原价=折后、优惠 0，天然幂等；金额全程 Long 分防 double 误差。
     */
    public static PricedLine priceLine(long originalUnitPrice, int qty, BigDecimal discount) {
        int safeQty = qty < 1 ? 1 : qty;
        long originalLine = originalUnitPrice * safeQty;
        long netUnit;
        if (discount == null || discount.compareTo(BigDecimal.ONE) >= 0) {
            netUnit = originalUnitPrice;
        } else {
            netUnit = BigDecimal.valueOf(originalUnitPrice)
                    .multiply(discount)
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
        }
        long netLine = netUnit * safeQty;
        return new PricedLine(originalUnitPrice, netUnit, safeQty, originalLine, netLine,
                originalLine - netLine);
    }

    /** 单行计价结果：折前单价/折后单价/数量/折前行小计/折后行小计/本行优惠（均为分）。 */
    public record PricedLine(long originalUnitPrice, long netUnitPrice, int qty,
                             long originalAmount, long netAmount, long discountAmount) {
    }
}
