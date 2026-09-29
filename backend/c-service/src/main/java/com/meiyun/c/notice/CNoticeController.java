package com.meiyun.c.notice;

import com.meiyun.c.audit.CAuditRecorder;
import com.meiyun.c.domain.CMemberAuth;
import com.meiyun.c.domain.CMemberAuthRepository;
import com.meiyun.c.security.CAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端消息设置域端点（DESIGN-C §四端点 #9，C-B6；V76 c_member_setting/V77 c_notification
 * c-service 自有表本地读写，免 internal 跨服务）：
 * GET /api/c/settings —— 设置页投影（随拍新增：页面装载初值必需，契约仅 PUT 如实注记）：
 *   三开关缺行回落默认 order/appt=true、promo=false（照前端写死初值）；phone=c_member_auth.phone
 *   掩码（前三后四）。实名认证/收货地址/缓存/关于/帮助无源静态留前端（§7 登记）。
 * PUT /api/c/settings —— 三开关持久化 upsert（uk(customer_id) 幂等锚，同值重复提交同结果天然
 *   幂等）；三值非布尔 400 中文；审计 C_SETTING UPDATE 落链。
 * GET /api/c/notifications —— 本人通知列表（行级隔离，created_at 倒序 LIMIT 50）：
 *   Notif 契约 id/type/title/body/time(东八区)/read/to=link。
 * PUT /api/c/notifications/{id}/read —— 单条已读（id 非数字 400；本人外 404 中文）；幂等天然。
 * PUT /api/c/notifications/read-all —— 全部已读；返回 updated 计数；审计 C_NOTIF 落链。
 */
@RestController
@RequestMapping("/api/c")
public class CNoticeController {

    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CN_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CMemberAuthRepository memberAuthRepository;
    private final JdbcTemplate jdbcTemplate;
    private final CAuditRecorder audit;

    public CNoticeController(CMemberAuthRepository memberAuthRepository, JdbcTemplate jdbcTemplate,
                             CAuditRecorder audit) {
        this.memberAuthRepository = memberAuthRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.audit = audit;
    }

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT notify_order, notify_appointment, notify_promo FROM c_member_setting WHERE customer_id = ?",
                customerId);
        boolean order = true;
        boolean appt = true;
        boolean promo = false;
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            order = Boolean.TRUE.equals(row.get("notify_order"));
            appt = Boolean.TRUE.equals(row.get("notify_appointment"));
            promo = Boolean.TRUE.equals(row.get("notify_promo"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        data.put("appt", appt);
        data.put("promo", promo);
        data.put("phone", maskPhone(g.member().getPhone()));
        return ResponseEntity.ok(ok(data));
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> putSettings(@RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        Object order = body.get("order");
        Object appt = body.get("appt");
        Object promo = body.get("promo");
        if (!(order instanceof Boolean) || !(appt instanceof Boolean) || !(promo instanceof Boolean)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err(400, "参数格式错误：order/appt/promo 须为布尔值"));
        }
        jdbcTemplate.update(
                "INSERT INTO c_member_setting (customer_id, notify_order, notify_appointment, notify_promo) "
                + "VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (customer_id) DO UPDATE SET notify_order = EXCLUDED.notify_order, "
                + "notify_appointment = EXCLUDED.notify_appointment, notify_promo = EXCLUDED.notify_promo, "
                + "updated_at = now()",
                customerId, order, appt, promo);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId);
        payload.put("order", order);
        payload.put("appt", appt);
        payload.put("promo", promo);
        audit.record("C_SETTING", "C-SETTING-" + customerId, actorOf(g), "UPDATE", payload);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        data.put("appt", appt);
        data.put("promo", promo);
        return ResponseEntity.ok(ok(data));
    }

    @GetMapping("/notifications")
    public ResponseEntity<Map<String, Object>> notifications(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, type, title, body, link, is_read, created_at FROM c_notification "
                + "WHERE customer_id = ? ORDER BY created_at DESC, id DESC LIMIT 50",
                customerId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(row.get("id")));
            item.put("type", row.get("type"));
            item.put("title", row.get("title"));
            item.put("body", row.get("body"));
            item.put("time", fmtCnTime(row.get("created_at")));
            item.put("read", Boolean.TRUE.equals(row.get("is_read")));
            item.put("to", row.get("link") == null ? "" : row.get("link"));
            items.add(item);
        }
        return ResponseEntity.ok(ok(items));
    }

    @PutMapping("/notifications/{id}/read")
    public ResponseEntity<Map<String, Object>> readOne(@PathVariable("id") String id, HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        long nid;
        try {
            nid = Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(400, "通知 id 格式错误"));
        }
        int updated = jdbcTemplate.update(
                "UPDATE c_notification SET is_read = TRUE, updated_at = now() "
                + "WHERE id = ? AND customer_id = ?", nid, customerId);
        if (updated == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(404, "通知不存在或无权操作"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId);
        payload.put("notificationId", nid);
        audit.record("C_NOTIF", "C-NOTIF-READ-" + nid, actorOf(g), "READ", payload);
        return ResponseEntity.ok(ok(Map.of("id", String.valueOf(nid), "read", true)));
    }

    @PutMapping("/notifications/read-all")
    public ResponseEntity<Map<String, Object>> readAll(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        int updated = jdbcTemplate.update(
                "UPDATE c_notification SET is_read = TRUE, updated_at = now() "
                + "WHERE customer_id = ? AND is_read = FALSE", customerId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId);
        payload.put("updated", updated);
        audit.record("C_NOTIF", "C-NOTIF-READALL-" + customerId, actorOf(g), "READ_ALL", payload);
        return ResponseEntity.ok(ok(Map.of("updated", updated)));
    }

    private static String actorOf(Guard g) {
        return "C:" + g.member().getOpenid();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String p = phone.trim();
        if (p.length() < 7) {
            return p;
        }
        return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
    }

    private static String fmtCnTime(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Timestamp t) {
            return t.toInstant().atZone(CN_ZONE).format(CN_TIME_FMT);
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.atZoneSameInstant(CN_ZONE).format(CN_TIME_FMT);
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.format(CN_TIME_FMT);
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
