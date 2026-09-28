package com.meiyun.c.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.c.audit.CAuditRecorder;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端订单域端点（DESIGN-C §四端点 #7，C-B4）：
 * GET  /api/c/orders —— 本人订单列表：txn_order 只读投影（customer_id 行级隔离，未绑会员
 *   档案如实空列表），store 富化门店中文名，order_item 子项一次 IN 参数化批量补齐（无 N+1），
 *   B 五态→C 六态映射，金额分→元。
 * GET  /api/c/orders/{id} —— 订单详情：本人归属校验（不存在 404/越权 403 全中文，照契约
 *   §五验收线），order_item 按 line_no 全量投影。
 * POST /api/c/orders —— 创建订单：定价 c-service 侧自查（PRICELIST_SQL 与价目页同源
 *   store_price ACTIVE × product_sku，promo??member??original，「用户所见价=落库价」），
 *   解析后经 RestTemplate 调 txn internal 端点落 B 端 txn_order+order_item（X-Internal-Token
 *   系统身份；txn 域守「禁 JdbcTemplate 跨域直读」铁律不自查价目）；txn 侧中文错误原码原话
 *   透传，网络异常 502 兜底。响应含 orderNo 对齐前端 pay.ts 契约（无 payParams→到店付分支）。
 * POST /api/c/orders/{id}/pay —— 发起支付：本人归属校验＋状态机（仅「待收款」可支付）；
 *   微信支付商户入网未完成（DESIGN-C §七 backlog），如实 503 中文＋审计 PAY_FAILED
 *   （红线②：禁止伪造支付成功）。
 * 既有表零改动契约：txn_order/order_item/store/store_price/product_sku 全部 JdbcTemplate
 * 只读 SELECT，写操作一律经 txn internal 端点落库（DESIGN-C L15/L71）。
 */
@RestController
@RequestMapping("/api/c/orders")
public class COrderController {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 与 CBrowseController 价目页同源同口径（逐字锚：store_price ACTIVE × product_sku）。 */
    private static final String PRICELIST_SQL =
            "SELECT sp.sku AS code, ps.name AS name, ps.service_category AS category, "
            + "sp.original_price_fen, sp.member_price_fen, sp.promo_price_fen, "
            + "ps.unit AS unit, ps.duration_min AS duration "
            + "FROM store_price sp JOIN product_sku ps ON ps.sku = sp.sku "
            + "WHERE sp.status = 'ACTIVE' ";

    private final CMemberAuthRepository memberAuthRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final CProps props;
    private final CAuditRecorder audit;

    @Value("${meiyun.txn.service-url:http://127.0.0.1:8083}")
    private String txnServiceUrl;

    public COrderController(CMemberAuthRepository memberAuthRepository, JdbcTemplate jdbcTemplate,
                            RestTemplate restTemplate, CProps props, CAuditRecorder audit) {
        this.memberAuthRepository = memberAuthRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
        this.props = props;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        String customerId = g.member().getCustomerId();
        if (customerId != null && !customerId.isBlank()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT o.order_no, o.customer_id, o.store_code, s.store_name, o.project, "
                    + "o.product_code, o.amount, o.status, o.created_at "
                    + "FROM txn_order o LEFT JOIN store s ON s.store_code = o.store_code "
                    + "WHERE o.customer_id = ? ORDER BY o.created_at DESC, o.order_no DESC",
                    customerId);
            Map<String, List<Map<String, Object>>> linesByOrder = loadLines(rows);
            for (Map<String, Object> row : rows) {
                items.add(toCItem(row, linesByOrder.getOrDefault(
                        String.valueOf(row.get("order_no")), List.of())));
            }
        }
        return ResponseEntity.ok(ok(items));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable("id") String id, HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT o.order_no, o.customer_id, o.store_code, s.store_name, o.project, "
                + "o.product_code, o.amount, o.status, o.created_at "
                + "FROM txn_order o LEFT JOIN store s ON s.store_code = o.store_code "
                + "WHERE o.order_no = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(404, "订单不存在或无权查看"));
        }
        Map<String, Object> row = rows.get(0);
        if (!customerId.equals(row.get("customer_id"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "订单不存在或无权操作"));
        }
        List<Map<String, Object>> lines = jdbcTemplate.queryForList(
                "SELECT order_no, item_name, qty, unit_price FROM order_item "
                + "WHERE order_no = ? ORDER BY line_no", id);
        return ResponseEntity.ok(ok(toCItem(row, lines)));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request,
                                                      @RequestBody Map<String, Object> body) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        String sku = str(body.get("itemId"));
        if (sku.isEmpty()) {
            return ResponseEntity.badRequest().body(err(400, "请选择购买项目"));
        }
        int qty = 1;
        String qtyRaw = str(body.get("qty"));
        if (!qtyRaw.isEmpty()) {
            try {
                qty = Integer.parseInt(qtyRaw);
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(err(400, "购买数量不正确"));
            }
        }
        if (qty < 1 || qty > 99) {
            return ResponseEntity.badRequest().body(err(400, "购买数量须为 1-99"));
        }
        String storeCode = str(body.get("storeCode"));
        if (storeCode.isEmpty()) {
            String storeName = str(body.get("storeName"));
            if (storeName.isEmpty()) {
                return ResponseEntity.badRequest().body(err(400, "请选择门店"));
            }
            List<Map<String, Object>> stores = jdbcTemplate.queryForList(
                    "SELECT store_code FROM store WHERE store_name = ? AND status = '营业中'", storeName);
            if (stores.isEmpty()) {
                return ResponseEntity.badRequest().body(err(400, "门店不存在或已停业: " + storeName));
            }
            storeCode = String.valueOf(stores.get(0).get("store_code"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                PRICELIST_SQL + "AND sp.sku = ?", sku);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(404, "项目不存在或已下架"));
        }
        Map<String, Object> price = rows.get(0);
        Long unitPriceFen = firstPositive(price.get("promo_price_fen"),
                price.get("member_price_fen"), price.get("original_price_fen"));
        if (unitPriceFen == null) {
            return ResponseEntity.badRequest().body(err(400, "项目价格信息缺失，暂不可购买"));
        }
        String projectName = str(price.get("name"));

        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("customerId", customerId);
        cmd.put("storeCode", storeCode);
        cmd.put("skuCode", sku);
        cmd.put("projectName", projectName);
        cmd.put("unitPriceFen", unitPriceFen);
        cmd.put("qty", qty);
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    txnServiceUrl + "/api/txn/internal/c-orders",
                    new HttpEntity<>(cmd, internalHeaders()), Map.class);
            Map<String, Object> view = resp.getBody();
            return ResponseEntity.ok(ok(view == null ? Map.of() : viewToCItem(view)));
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(err(e.getStatusCode().value(), extractMessage(e, "订单创建失败，请稍后重试")));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err(502, "订单服务暂不可用，请稍后重试"));
        }
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<Map<String, Object>> pay(@PathVariable("id") String id, HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT customer_id, status, amount FROM txn_order WHERE order_no = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(404, "订单不存在或无权查看"));
        }
        Map<String, Object> order = rows.get(0);
        if (!customerId.equals(order.get("customer_id"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "订单不存在或无权操作"));
        }
        String status = String.valueOf(order.get("status"));
        if (!"待收款".equals(status)) {
            return ResponseEntity.badRequest().body(err(400, "当前订单状态不可支付: " + status));
        }
        // 微信支付商户入网/预下单 backlog（DESIGN-C §七 item 2）：如实失败＋审计，禁止伪造支付成功（红线②）。
        String reason = props.getWechat().isPayEnabled()
                ? "WECHAT_PREPAY_NOT_IMPLEMENTED" : "WECHAT_PAY_NOT_ENABLED";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", id);
        payload.put("amountFen", num(order.get("amount")));
        payload.put("reason", reason);
        payload.put("channel", "C");
        audit.record("ORDER", id, "C:" + request.getAttribute(CAuthFilter.ATTR_OPENID), "PAY_FAILED", payload);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(err(503, "微信支付暂未开通（商户入网流程进行中），请到店后由收银台完成收款"));
    }

    /** 订单子项一次 IN 参数化批量补齐（列表专用，避免 N+1；order_no 系统生成，参数化绑定无注入面）。 */
    private Map<String, List<Map<String, Object>>> loadLines(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> byOrder = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return byOrder;
        }
        List<Object> orderNos = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            orderNos.add(String.valueOf(row.get("order_no")));
        }
        String placeholders = String.join(", ", Collections.nCopies(orderNos.size(), "?"));
        List<Map<String, Object>> lines = jdbcTemplate.queryForList(
                "SELECT order_no, item_name, qty, unit_price FROM order_item "
                + "WHERE order_no IN (" + placeholders + ") ORDER BY order_no, line_no",
                orderNos.toArray());
        for (Map<String, Object> line : lines) {
            byOrder.computeIfAbsent(String.valueOf(line.get("order_no")), k -> new ArrayList<>()).add(line);
        }
        return byOrder;
    }

    /** 登录守卫（照 CAppointmentController 同口径：未登录 401/凭证无效 401/停用 403 全中文）。 */
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

    /** B 端 txn_order.status 五态 → C 端 OrderStatus 六态（未知态如实归待收款，照 appointment 首态兜底先例）。 */
    private static String toCStatus(String bStatus) {
        return switch (bStatus) {
            case "待签核" -> "PENDING_SIGN";
            case "待收款" -> "PENDING_PAY";
            case "已收款" -> "PAID";
            case "已核销" -> "COMPLETED";
            case "已取消" -> "CANCELLED";
            default -> "PENDING_PAY";
        };
    }

    /** 列表/详情行投影（JdbcTemplate 行 + order_item 子项 → 前端 COrder 契约）。 */
    private static Map<String, Object> toCItem(Map<String, Object> row, List<Map<String, Object>> lines) {
        Map<String, Object> item = new LinkedHashMap<>();
        String orderNo = String.valueOf(row.get("order_no"));
        item.put("id", orderNo);
        item.put("orderNo", orderNo);
        item.put("customerId", row.get("customer_id"));
        item.put("storeCode", row.get("store_code"));
        item.put("storeName", row.get("store_name"));
        item.put("store", row.get("store_name"));
        item.put("project", row.get("project"));
        List<Map<String, Object>> cLines = new ArrayList<>();
        Object spec = row.get("product_code");
        for (Map<String, Object> l : lines) {
            Map<String, Object> cl = new LinkedHashMap<>();
            cl.put("name", l.get("item_name"));
            cl.put("spec", spec);
            Object qty = l.get("qty");
            cl.put("qty", qty instanceof Number n ? n.intValue() : 1);
            cl.put("price", fenToYuan(num(l.get("unit_price"))));
            cLines.add(cl);
        }
        item.put("items", cLines);
        item.put("amount", fenToYuan(num(row.get("amount"))));
        item.put("status", toCStatus(String.valueOf(row.get("status"))));
        item.put("createdAt", String.valueOf(row.get("created_at")));
        return item;
    }

    /** txn internal COrderView 投影（创建响应 → 前端 COrder 契约；amountFen 分→元，qty 整除还原单价）。 */
    private static Map<String, Object> viewToCItem(Map<String, Object> view) {
        Map<String, Object> item = new LinkedHashMap<>();
        Object orderNo = view.get("orderNo");
        item.put("id", orderNo);
        item.put("orderNo", orderNo);
        item.put("customerId", view.get("customerId"));
        item.put("storeCode", view.get("storeCode"));
        item.put("storeName", view.get("storeName"));
        item.put("store", view.get("storeName"));
        item.put("project", view.get("project"));
        long amountFen = num(view.get("amountFen"));
        Object qtyRaw = view.get("qty");
        int qty = qtyRaw instanceof Number n ? n.intValue() : 1;
        List<Map<String, Object>> lines = new ArrayList<>();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("name", view.get("project"));
        line.put("spec", view.get("skuCode"));
        line.put("qty", qty);
        line.put("price", fenToYuan(qty > 0 ? amountFen / qty : amountFen));
        lines.add(line);
        item.put("items", lines);
        item.put("amount", fenToYuan(amountFen));
        item.put("status", toCStatus(String.valueOf(view.get("status"))));
        item.put("createdAt", view.get("createdAt"));
        return item;
    }

    /** promo ?? member ?? original（分，取首个正数；全空/<=0 返回 null 如实拒售，不臆造价格）。 */
    private static Long firstPositive(Object... vals) {
        for (Object v : vals) {
            if (v instanceof Number n && n.longValue() > 0) {
                return n.longValue();
            }
        }
        return null;
    }

    /** txn GlobalExceptionHandler 错误体 {error,message} 中文化透传。 */
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
