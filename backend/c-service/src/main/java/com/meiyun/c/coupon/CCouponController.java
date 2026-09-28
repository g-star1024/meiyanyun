package com.meiyun.c.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.c.config.CProps;
import com.meiyun.c.domain.CMemberAuth;
import com.meiyun.c.domain.CMemberAuthRepository;
import com.meiyun.c.security.CAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端优惠券域端点（DESIGN-C §四端点 #8，C-B5）：
 * GET  /api/c/coupons/claimable —— 可领券列表：coupon_template(ACTIVE) JdbcTemplate 只读投影，
 *   LEFT JOIN coupon_hold(V75) 标本人已领（claimed，同人同券至多一张）；PACKAGE 组合券走 B 端
 *   定向发放渠道，C 端领取仅支持 AMOUNT/RATE 两类（如实过滤，不臆造子项合计价）。RATE 折扣
 *   value 原值下发（85=8.5 折，前端 /10 展示），AMOUNT 分→元；threshold 分→元。
 * POST /api/c/coupons/{id}/claim —— 领取：经 RestTemplate 调 marketing internal 端点落
 *   coupon_hold（idem_key=couponId:customerId 幂等重放直返；与 B 端发放共用 CouponService
 *   synchronized 实例锁防超发）；marketing 中文错误原码原话透传，网络异常 502 兜底。
 * GET  /api/c/coupons/mine —— 我的卡包：coupon_hold JOIN coupon_template 富化名称/面值/有效期，
 *   hold 状态原值下发（HELD/USED/EXPIRED/REVOKED，前端三 tab 映射）。
 * 既有表零改动契约：coupon_template/coupon_hold 只读 SELECT，写操作一律经 marketing internal
 * 端点落库（DESIGN-C L15/L71）。行级隔离：customer_id 一律取登录态绑定档案，未绑定 403 中文。
 */
@RestController
@RequestMapping("/api/c/coupons")
public class CCouponController {

    private static final ObjectMapper OM = new ObjectMapper();

    private final CMemberAuthRepository memberAuthRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final CProps props;

    @Value("${meiyun.marketing.service-url:http://127.0.0.1:8088}")
    private String marketingServiceUrl;

    public CCouponController(CMemberAuthRepository memberAuthRepository, JdbcTemplate jdbcTemplate,
                             RestTemplate restTemplate, CProps props) {
        this.memberAuthRepository = memberAuthRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
        this.props = props;
    }

    @GetMapping("/claimable")
    public ResponseEntity<Map<String, Object>> claimable(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.coupon_id, t.coupon_name, t.coupon_type, t.face_value, t.threshold, "
                + "t.total_qty, t.issued_qty, t.valid_start, t.valid_end, "
                + "(h.id IS NOT NULL) AS claimed "
                + "FROM coupon_template t "
                + "LEFT JOIN coupon_hold h ON h.coupon_id = t.coupon_id AND h.customer_id = ? "
                + "WHERE t.status = 'ACTIVE' AND t.coupon_type IN ('AMOUNT','RATE') "
                + "ORDER BY t.created_at DESC",
                customerId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = toCouponItem(row);
            item.put("claimed", Boolean.TRUE.equals(row.get("claimed")));
            item.put("status", "ACTIVE");
            items.add(item);
        }
        return ResponseEntity.ok(ok(items));
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<Map<String, Object>> claim(@PathVariable("id") String id, HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("couponId", id);
        cmd.put("customerId", customerId);
        cmd.put("openid", String.valueOf(request.getAttribute(CAuthFilter.ATTR_OPENID)));
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    marketingServiceUrl + "/api/marketing/internal/c-coupons/claim",
                    new HttpEntity<>(cmd, internalHeaders()), Map.class);
            Map<String, Object> view = resp.getBody();
            return ResponseEntity.ok(ok(view == null ? Map.of() : view));
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(err(e.getStatusCode().value(), extractMessage(e, "领取失败，请稍后重试")));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err(502, "营销服务暂不可用，请稍后重试"));
        }
    }

    @GetMapping("/mine")
    public ResponseEntity<Map<String, Object>> mine(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT h.id AS hold_id, h.coupon_id, h.status, h.used_at, h.created_at, "
                + "t.coupon_name, t.coupon_type, t.face_value, t.threshold, t.valid_start, t.valid_end "
                + "FROM coupon_hold h JOIN coupon_template t ON t.coupon_id = h.coupon_id "
                + "WHERE h.customer_id = ? ORDER BY h.created_at DESC",
                customerId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = toCouponItem(row);
            item.put("holdId", row.get("hold_id"));
            item.put("status", row.get("status"));
            item.put("usedAt", row.get("used_at") == null ? null : String.valueOf(row.get("used_at")));
            item.put("createdAt", String.valueOf(row.get("created_at")));
            items.add(item);
        }
        return ResponseEntity.ok(ok(items));
    }

    /** 券公共投影（claimable/mine 共用）：RATE→DISCOUNT 且 value 原值（85=8.5 折），AMOUNT 分→元。 */
    private static Map<String, Object> toCouponItem(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.get("coupon_id"));
        item.put("name", row.get("coupon_name"));
        String type = String.valueOf(row.get("coupon_type"));
        long faceValue = num(row.get("face_value"));
        if ("RATE".equals(type)) {
            item.put("type", "DISCOUNT");
            item.put("value", faceValue);
        } else {
            item.put("type", "AMOUNT");
            item.put("value", fenToYuan(faceValue));
        }
        item.put("threshold", fenToYuan(num(row.get("threshold"))));
        item.put("total", row.get("total_qty") == null ? 0 : ((Number) row.get("total_qty")).intValue());
        item.put("granted", row.get("issued_qty") == null ? 0 : ((Number) row.get("issued_qty")).intValue());
        item.put("startDate", row.get("valid_start") == null ? "" : String.valueOf(row.get("valid_start")));
        item.put("endDate", row.get("valid_end") == null ? "" : String.valueOf(row.get("valid_end")));
        return item;
    }

    /** 登录守卫（照 COrderController 同口径：未登录 401/凭证无效 401/停用 403 全中文）。 */
    private Guard guard(HttpServletRequest request) {
        Object openidAttr = request.getAttribute(CAuthFilter.ATTR_OPENID);
        if (openidAttr == null) {
            return new Guard(null, ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err(401, "请先登录")));
        }
        CMemberAuth member = memberAuthRepository.findByOpenid(String.valueOf(openidAttr)).orElse(null);
        if (member == null) {
            return new Guard(null, ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err(401, "登录凭证无效或已过期")));
        }
        if ("DISABLED".equals(member.getStatus())) {
            return new Guard(null, ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号已停用，请联系门店")));
        }
        return new Guard(member, null);
    }

    private record Guard(CMemberAuth member, ResponseEntity<Map<String, Object>> error) {}

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", props.getInternalToken());
        return headers;
    }

    /** marketing GlobalExceptionHandler 错误体 {error,message} 中文化透传。 */
    private static String extractMessage(HttpStatusCodeException e, String fallback) {
        try {
            Map<?, ?> body = OM.readValue(e.getResponseBodyAsString(), Map.class);
            Object msg = body.get("message");
            if (msg != null && !String.valueOf(msg).isBlank()) {
                return String.valueOf(msg);
            }
        } catch (Exception ignore) {
            // 解析失败走兜底中文
        }
        return fallback;
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static BigDecimal fenToYuan(long fen) {
        return BigDecimal.valueOf(fen).divide(BigDecimal.valueOf(100));
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", "ok");
        body.put("data", data);
        return body;
    }

    private static Map<String, Object> err(int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        return body;
    }
}
