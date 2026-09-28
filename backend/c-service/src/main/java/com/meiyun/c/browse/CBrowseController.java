package com.meiyun.c.browse;

import com.meiyun.c.domain.CMemberAuth;
import com.meiyun.c.domain.CMemberAuthRepository;
import com.meiyun.c.security.CAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端会员浏览域端点（DESIGN-C §二 #5，C-B2）：
 * GET /api/c/member/profile —— 当前会员档案投影：c_member_auth 已绑 customer 时投影
 *   customer(name/phone/level/points)＋member_card 在用卡余额合计（分→元）；未绑定/档案不可读
 *   时如实回落自身投影（积/余额/券数 0）。couponCount 接 coupon_hold(V75) 本人 HELD 计数（C-B5 已接通）。
 * GET /api/c/pricelist —— 在售价目：store_price(ACTIVE) JOIN product_sku 富化名称/分类/单位/时长，
 *   金额分→元，照 B 端 PricelistService 口径；分类缺失行防御性剔除。
 * GET /api/c/projects/{id} —— 价目详情（id=sku），非在售/不存在 404 中文。
 * GET /api/c/stores —— 可预约门店（status='营业中'；自端点 #9 提前至 C-B3：预约新建页
 *   门店选择链路必需真实数据源，否则写死三店名与库内 SST01-06 不符验收不可达，DESIGN-C 随拍回填订正）。
 * 既有表零改动契约：全部 JdbcTemplate 只读 SELECT（照 C-B1 auth/me L187 先例），不落实体不触发 ddl。
 * 行级隔离：profile 只取 token 自有 customer_id，天然无越权面。
 */
@RestController
@RequestMapping("/api/c")
public class CBrowseController {

    private final CMemberAuthRepository memberAuthRepository;
    private final JdbcTemplate jdbcTemplate;

    public CBrowseController(CMemberAuthRepository memberAuthRepository, JdbcTemplate jdbcTemplate) {
        this.memberAuthRepository = memberAuthRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String PRICELIST_SQL =
            "SELECT sp.sku AS code, ps.name AS name, ps.service_category AS category, "
            + "sp.original_price_fen, sp.member_price_fen, sp.promo_price_fen, "
            + "ps.unit AS unit, ps.duration_min AS duration "
            + "FROM store_price sp JOIN product_sku ps ON ps.sku = sp.sku "
            + "WHERE sp.status = 'ACTIVE' ";

    @GetMapping("/member/profile")
    public ResponseEntity<Map<String, Object>> profile(HttpServletRequest request) {
        Object openidAttr = request.getAttribute(CAuthFilter.ATTR_OPENID);
        if (openidAttr == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err(401, "请先登录"));
        }
        String openid = String.valueOf(openidAttr);
        CMemberAuth member = memberAuthRepository.findByOpenid(openid).orElse(null);
        if (member == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err(401, "登录凭证无效或已过期"));
        }
        if ("DISABLED".equals(member.getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号已停用，请联系门店"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("memberId", "");
        data.put("name", member.getNickname() == null || member.getNickname().isBlank() ? "新会员" : member.getNickname());
        data.put("phone", member.getPhone() == null ? "" : member.getPhone());
        data.put("points", 0);
        data.put("cardBalance", BigDecimal.ZERO.setScale(2));
        data.put("couponCount", 0);
        data.put("level", "");
        String customerId = member.getCustomerId();
        if (customerId != null && !customerId.isBlank()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT name, phone, level, points FROM customer "
                    + "WHERE customer_id = ? AND merged_into IS NULL AND anonymized_at IS NULL",
                    customerId);
            if (!rows.isEmpty()) {
                Map<String, Object> c = rows.get(0);
                data.put("memberId", customerId);
                data.put("name", c.get("name"));
                data.put("phone", c.get("phone") == null ? "" : c.get("phone"));
                data.put("level", c.get("level") == null ? "" : c.get("level"));
                Object pts = c.get("points");
                data.put("points", pts == null ? 0 : ((Number) pts).longValue());
                Long cardFen = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(balance + gift_balance), 0) FROM member_card "
                        + "WHERE customer_id = ? AND status = '在用'",
                        Long.class, customerId);
                data.put("cardBalance", fenToYuan(cardFen == null ? 0L : cardFen));
                Long couponCnt = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupon_hold WHERE customer_id = ? AND status = 'HELD'",
                        Long.class, customerId);
                data.put("couponCount", couponCnt == null ? 0 : couponCnt.intValue());
            }
        }
        return ResponseEntity.ok(ok(data));
    }

    @GetMapping("/pricelist")
    public Map<String, Object> pricelist() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                PRICELIST_SQL + "ORDER BY ps.service_category NULLS LAST, sp.sku");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = toPriceItem(row);
            if (item != null) {
                items.add(item);
            }
        }
        return ok(items);
    }

    @GetMapping("/projects/{id}")
    public ResponseEntity<Map<String, Object>> projectDetail(@PathVariable("id") String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                PRICELIST_SQL + "AND sp.sku = ?", id);
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = toPriceItem(row);
            if (item != null) {
                return ResponseEntity.ok(ok(item));
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(404, "项目不存在或已下架"));
    }

    @GetMapping("/stores")
    public Map<String, Object> stores() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT store_code AS code, store_name AS name FROM store "
                + "WHERE status = '营业中' ORDER BY store_code");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", row.get("code"));
            item.put("name", row.get("name"));
            items.add(item);
        }
        return ok(items);
    }

    private static Map<String, Object> toPriceItem(Map<String, Object> row) {
        Object category = row.get("category");
        if (category == null || String.valueOf(category).isBlank()) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        String code = String.valueOf(row.get("code"));
        item.put("id", code);
        item.put("code", code);
        item.put("name", row.get("name"));
        item.put("category", category);
        item.put("originalPrice", fenToYuan(num(row.get("original_price_fen"))));
        item.put("memberPrice", fenToYuan(num(row.get("member_price_fen"))));
        Object promo = row.get("promo_price_fen");
        item.put("promoPrice", promo == null ? null : fenToYuan(num(promo)));
        item.put("unit", row.get("unit"));
        Object duration = row.get("duration");
        item.put("duration", duration == null ? 0 : ((Number) duration).intValue());
        return item;
    }

    private static long num(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
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
