package com.meiyun.c.asset;

import com.meiyun.c.domain.CMemberAuth;
import com.meiyun.c.domain.CMemberAuthRepository;
import com.meiyun.c.security.CAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端会员资产域端点（DESIGN-C §四端点 #9，C-B6）：
 * GET /api/c/packages —— 我的套餐：member_card 全源只读投影（行级隔离），MyPackage 契约
 *   name=card_item/total=total_times/used=total-remain/expire=expires_at 原值（空=长期有效）/
 *   type=card_type/balance=balance+gift_balance 分→元字符串；次卡 total>1 进度条、储值卡显 balance。
 * GET /api/c/records —— 消费记录：card_ledger(CONSUME/RECHARGE/REFUND) ∪ txn_order 现付单
 *   （非卡支付·NOT EXISTS card_ledger 去重）四路归并，ADJUST/TRANSFER 页面 tabs 无对应类如实不投；
 *   金额分→元，会员视角支出=负/收入=正/次卡扣次 amount=0（前端显「扣次」）；门店中文名富化，
 *   pay_method 五码→中文照 COrderController 先例；按日分组 [{date,list}] 倒序直投页面形状。
 * 既有表零改动契约：member_card/card_ledger/txn_order/order_payment/store 全部 JdbcTemplate
 * 只读 SELECT（照 CMemberController 先例），不落实体不触发 ddl。
 */
@RestController
@RequestMapping("/api/c")
public class CAssetController {

    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CN_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter CN_HM_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter CN_SORT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CMemberAuthRepository memberAuthRepository;
    private final JdbcTemplate jdbcTemplate;

    public CAssetController(CMemberAuthRepository memberAuthRepository, JdbcTemplate jdbcTemplate) {
        this.memberAuthRepository = memberAuthRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/packages")
    public ResponseEntity<Map<String, Object>> packages(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT card_item, card_type, total_times, remain_times, balance, gift_balance, expires_at "
                + "FROM member_card WHERE customer_id = ? ORDER BY created_at DESC",
                customerId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            int total = row.get("total_times") instanceof Number n ? n.intValue() : 0;
            int remain = row.get("remain_times") instanceof Number n ? n.intValue() : 0;
            item.put("name", row.get("card_item"));
            item.put("type", row.get("card_type"));
            item.put("total", total);
            item.put("used", Math.max(total - remain, 0));
            item.put("expire", fmtCn(row.get("expires_at"), CN_DATE_FMT));
            item.put("balance", fenToYuan(num(row.get("balance")) + num(row.get("gift_balance"))).toPlainString());
            items.add(item);
        }
        return ResponseEntity.ok(ok(items));
    }

    @GetMapping("/records")
    public ResponseEntity<Map<String, Object>> records(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT l.created_at AS ts, COALESCE(o.project, mc.card_item, '卡项消费') AS name, "
                + "s.store_name AS store, l.amount AS amount_fen, "
                + "CASE WHEN l.amount = 0 THEN '疗程卡扣次' ELSE '卡余额支付' END AS note "
                + "FROM card_ledger l "
                + "LEFT JOIN txn_order o ON o.order_no = l.order_no "
                + "LEFT JOIN member_card mc ON mc.card_no = l.card_no "
                + "LEFT JOIN store s ON s.store_code = COALESCE(l.store_code, o.store_code) "
                + "WHERE l.customer_id = ? AND l.change_type = 'CONSUME'", customerId)) {
            lines.add(toLine(row, "项目消费", -Math.abs(num(row.get("amount_fen"))), String.valueOf(row.get("note"))));
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT l.created_at AS ts, COALESCE(mc.card_item, '卡项充值') AS name, "
                + "s.store_name AS store, l.amount AS amount_fen, pm.pay_method AS pay_method "
                + "FROM card_ledger l "
                + "LEFT JOIN member_card mc ON mc.card_no = l.card_no "
                + "LEFT JOIN store s ON s.store_code = l.store_code "
                + "LEFT JOIN LATERAL (SELECT p.pay_method FROM order_payment p WHERE p.order_no = l.order_no "
                + "ORDER BY p.created_at DESC LIMIT 1) pm ON TRUE "
                + "WHERE l.customer_id = ? AND l.change_type = 'RECHARGE'", customerId)) {
            String pay = payMethodText(row.get("pay_method") == null ? null : String.valueOf(row.get("pay_method")));
            lines.add(toLine(row, "卡项充值", -Math.abs(num(row.get("amount_fen"))), pay == null ? "门店收款" : pay));
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT l.created_at AS ts, COALESCE(mc.card_item, '卡项退款') AS name, "
                + "s.store_name AS store, l.amount AS amount_fen "
                + "FROM card_ledger l "
                + "LEFT JOIN member_card mc ON mc.card_no = l.card_no "
                + "LEFT JOIN store s ON s.store_code = l.store_code "
                + "WHERE l.customer_id = ? AND l.change_type = 'REFUND'", customerId)) {
            lines.add(toLine(row, "退款", Math.abs(num(row.get("amount_fen"))), "退款原路退回"));
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT o.created_at AS ts, o.project AS name, s.store_name AS store, "
                + "o.amount AS amount_fen, pm.pay_method AS pay_method "
                + "FROM txn_order o "
                + "LEFT JOIN store s ON s.store_code = o.store_code "
                + "LEFT JOIN LATERAL (SELECT p.pay_method FROM order_payment p WHERE p.order_no = o.order_no "
                + "ORDER BY p.created_at DESC LIMIT 1) pm ON TRUE "
                + "WHERE o.customer_id = ? AND o.status IN ('已收款','已核销') "
                + "AND NOT EXISTS (SELECT 1 FROM card_ledger l2 WHERE l2.order_no = o.order_no)", customerId)) {
            String pay = payMethodText(row.get("pay_method") == null ? null : String.valueOf(row.get("pay_method")));
            lines.add(toLine(row, "项目消费", -Math.abs(num(row.get("amount_fen"))), pay == null ? "门店收款" : pay));
        }
        lines.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("sortKey"))).reversed());
        Map<String, List<Map<String, Object>>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> line : lines) {
            line.remove("sortKey");
            byDate.computeIfAbsent(String.valueOf(line.get("date")), k -> new ArrayList<>()).add(line);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byDate.entrySet()) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("date", e.getKey());
            group.put("list", e.getValue());
            groups.add(group);
        }
        return ResponseEntity.ok(ok(groups));
    }

    private static Map<String, Object> toLine(Map<String, Object> row, String type, long signedFen, String note) {
        Object ts = row.get("ts");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("name", row.get("name"));
        line.put("store", row.get("store") == null ? "" : row.get("store"));
        line.put("amount", fenToYuan(signedFen));
        line.put("note", note);
        line.put("time", fmtCn(ts, CN_HM_FMT));
        line.put("type", type);
        line.put("date", fmtCn(ts, CN_DATE_FMT));
        line.put("sortKey", fmtCn(ts, CN_SORT_FMT));
        return line;
    }

    private static String payMethodText(String method) {
        if (method == null || method.isBlank()) {
            return null;
        }
        return switch (method) {
            case "cash" -> "现金";
            case "wxpay" -> "微信支付";
            case "alipay" -> "支付宝";
            case "card" -> "银行卡";
            case "balance" -> "储值余额";
            default -> method;
        };
    }

    private static String fmtCn(Object v, DateTimeFormatter fmt) {
        if (v == null) {
            return "";
        }
        if (v instanceof Timestamp t) {
            return t.toInstant().atZone(CN_ZONE).format(fmt);
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.atZoneSameInstant(CN_ZONE).format(fmt);
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.format(fmt);
        }
        return String.valueOf(v);
    }

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
