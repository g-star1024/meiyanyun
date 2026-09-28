package com.meiyun.c.mall;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * C 端积分商城域端点（DESIGN-C §四端点 #8，C-B5）：
 * GET  /api/c/mall/products —— 兑换商品：mall_product(已上架) JdbcTemplate 只读投影，
 *   product_type 中文→前端四分类（项目/实物/优惠券/服务 → PROJECT/PHYSICAL/COUPON/SERVICE），
 *   低库存（stock≤50）照 B 端实体注释口径为前端派生，本层不下发 LOW_STOCK。
 * POST /api/c/mall/exchange —— 积分兑换：clientToken="C:"+UUID 幂等键留痕 C 端来源，经
 *   RestTemplate 调 customer internal 端点复用 placeExchange 全量校验链（上架/库存/实物三要素），
 *   落库为「待审核」，积分扣减在 B 端审核通过时生效；customer 中文错误原码原话透传，网络异常 502 兜底。
 * GET  /api/c/mall/exchanges/mine —— 我的兑换：mall_exchange JOIN mall_product 富化商品名，
 *   B 四态中文→C 四态英文（待审核/已通过/已拒绝/已发放 → PENDING/APPROVED/REJECTED/FULFILLED）。
 * 既有表零改动契约：mall_product/mall_exchange 只读 SELECT，写操作一律经 customer internal
 * 端点落库（DESIGN-C L15/L71）。行级隔离：customer_id 取登录态绑定档案，未绑定 403 中文。
 */
@RestController
@RequestMapping("/api/c/mall")
public class CMallController {

    private static final ObjectMapper OM = new ObjectMapper();

    private final CMemberAuthRepository memberAuthRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final CProps props;

    @Value("${meiyun.customer.service-url:http://127.0.0.1:8082}")
    private String customerServiceUrl;

    public CMallController(CMemberAuthRepository memberAuthRepository, JdbcTemplate jdbcTemplate,
                           RestTemplate restTemplate, CProps props) {
        this.memberAuthRepository = memberAuthRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
        this.props = props;
    }

    @GetMapping("/products")
    public ResponseEntity<Map<String, Object>> products(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT product_id, product_name, product_type, points_price, stock, cover, description "
                + "FROM mall_product WHERE status = '已上架' ORDER BY created_at DESC");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            String type = String.valueOf(row.get("product_type"));
            item.put("id", row.get("product_id"));
            item.put("name", row.get("product_name"));
            item.put("category", toCategory(type));
            item.put("pointsCost", row.get("points_price") == null ? 0 : ((Number) row.get("points_price")).intValue());
            item.put("stock", row.get("stock") == null ? 0 : ((Number) row.get("stock")).intValue());
            item.put("status", "ON_SALE");
            item.put("imageText", imageTextOf(type));
            item.put("cover", row.get("cover"));
            item.put("description", row.get("description"));
            items.add(item);
        }
        return ResponseEntity.ok(ok(items));
    }

    @PostMapping("/exchange")
    public ResponseEntity<Map<String, Object>> exchange(HttpServletRequest request,
                                                        @RequestBody Map<String, Object> body) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        String productId = str(body.get("productId"));
        if (productId.isEmpty()) {
            return ResponseEntity.badRequest().body(err(400, "请选择兑换商品"));
        }
        int qty = 1;
        String qtyRaw = str(body.get("qty"));
        if (!qtyRaw.isEmpty()) {
            try {
                qty = Integer.parseInt(qtyRaw);
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(err(400, "兑换数量不正确"));
            }
        }
        if (qty < 1 || qty > 99) {
            return ResponseEntity.badRequest().body(err(400, "兑换数量须为 1-99"));
        }
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("productId", productId);
        cmd.put("customerId", customerId);
        cmd.put("qty", qty);
        cmd.put("shipName", str(body.get("shipName")));
        cmd.put("shipPhone", str(body.get("shipPhone")));
        cmd.put("shipAddress", str(body.get("shipAddress")));
        cmd.put("clientToken", "C:" + UUID.randomUUID());
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    customerServiceUrl + "/api/customer/internal/c-mall/exchange",
                    new HttpEntity<>(cmd, internalHeaders()), Map.class);
            Map<String, Object> view = resp.getBody();
            return ResponseEntity.ok(ok(view == null ? Map.of() : toRedemptionItem(view)));
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(err(e.getStatusCode().value(), extractMessage(e, "兑换失败，请稍后重试")));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err(502, "客户服务暂不可用，请稍后重试"));
        }
    }

    @GetMapping("/exchanges/mine")
    public ResponseEntity<Map<String, Object>> exchangesMine(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT e.exchange_id, e.points_spent, e.qty, e.status, e.created_at, p.product_name "
                + "FROM mall_exchange e LEFT JOIN mall_product p ON p.product_id = e.product_id "
                + "WHERE e.customer_id = ? ORDER BY e.created_at DESC",
                customerId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("exchange_id"));
            item.put("orderNo", row.get("exchange_id"));
            item.put("productName", row.get("product_name"));
            item.put("pointsCost", row.get("points_spent") == null ? 0 : ((Number) row.get("points_spent")).intValue());
            item.put("qty", row.get("qty") == null ? 1 : ((Number) row.get("qty")).intValue());
            item.put("status", toRedemptionStatus(String.valueOf(row.get("status"))));
            item.put("createdAt", String.valueOf(row.get("created_at")));
            items.add(item);
        }
        return ResponseEntity.ok(ok(items));
    }

    /** customer internal MallExchange 投影（兑换响应 → 前端 RedemptionRecord 契约）。 */
    private static Map<String, Object> toRedemptionItem(Map<String, Object> view) {
        Map<String, Object> item = new LinkedHashMap<>();
        Object exchangeId = view.get("exchangeId");
        item.put("id", exchangeId);
        item.put("orderNo", exchangeId);
        item.put("productName", view.get("productName"));
        Object spent = view.get("pointsSpent");
        item.put("pointsCost", spent instanceof Number n ? n.intValue() : 0);
        Object qty = view.get("qty");
        item.put("qty", qty instanceof Number n ? n.intValue() : 1);
        item.put("status", toRedemptionStatus(String.valueOf(view.get("status"))));
        item.put("createdAt", view.get("createdAt"));
        return item;
    }

    /** mall_product.product_type 中文 → 前端 ProductCategory 四分类。 */
    private static String toCategory(String productType) {
        return switch (productType) {
            case "项目" -> "PROJECT";
            case "实物" -> "PHYSICAL";
            case "优惠券" -> "COUPON";
            case "服务" -> "SERVICE";
            default -> "SERVICE";
        };
    }

    /** 列表图占位文字（cover 为空时前端首字/文字兜底，与页面 tagOf 口径一致）。 */
    private static String imageTextOf(String productType) {
        return switch (productType) {
            case "项目" -> "项目";
            case "优惠券" -> "券";
            case "服务" -> "服务";
            default -> "实物";
        };
    }

    /** B 端 mall_exchange.status 四态中文 → C 端 RedemptionRecord.status 四态英文。 */
    private static String toRedemptionStatus(String bStatus) {
        return switch (bStatus) {
            case "待审核" -> "PENDING";
            case "已通过" -> "APPROVED";
            case "已拒绝" -> "REJECTED";
            case "已发放" -> "FULFILLED";
            default -> "PENDING";
        };
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

    /** customer GlobalExceptionHandler 错误体 {error,message} 中文化透传。 */
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

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
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
