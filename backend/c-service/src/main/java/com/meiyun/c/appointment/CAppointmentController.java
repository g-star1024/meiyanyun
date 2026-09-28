package com.meiyun.c.appointment;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C 端预约域端点（DESIGN-C §四端点 #6，C-B3）：
 * GET  /api/c/appointments —— 本人预约列表：appointment 只读投影（customer_id 行级隔离，
 *   未绑会员档案如实空列表），store 富化门店中文名，B 四态→C 六态映射（CONFIRMED/COMPLETED
 *   无 B 端对应不映射，如实登记 DESIGN-C §7），timeSlot 拼 `${date}T${time}:00` 对齐前端契约。
 * POST /api/c/appointments —— 创建预约：店名→store_code 解析（营业中）后 RestTemplate 调
 *   txn internal 端点落 B 端 appointment 表（X-Internal-Token 系统身份；source 适配层口径
 *   转换：C 端入口固定「C端小程序」）；txn 侧中文错误（400 校验/409 幂等）原码原话透传。
 * POST /api/c/appointments/{id}/cancel —— 取消预约：本人归属校验（越权 403 中文，照契约
 *   §五验收线）后调 txn internal（仅「已预约」可取消，状态机中文 400 透传）。
 * 既有表零改动契约：本侧对 appointment/store 全部 JdbcTemplate 只读 SELECT，写操作一律
 * 经 txn internal 端点落库（DESIGN-C L15/L71）；note 字段 appointment 表无列，如实不落（§7）。
 */
@RestController
@RequestMapping("/api/c/appointments")
public class CAppointmentController {

    private static final Pattern TIME_SLOT = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})(:\\d{2})?$");
    private static final ObjectMapper OM = new ObjectMapper();

    private final CMemberAuthRepository memberAuthRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final CProps props;

    @Value("${meiyun.txn.service-url:http://127.0.0.1:8083}")
    private String txnServiceUrl;

    public CAppointmentController(CMemberAuthRepository memberAuthRepository, JdbcTemplate jdbcTemplate,
                                  RestTemplate restTemplate, CProps props) {
        this.memberAuthRepository = memberAuthRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
        this.props = props;
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
                    "SELECT a.appt_no, a.customer_id, a.store_code, s.store_name, a.project, "
                    + "a.appt_date, a.appt_time, a.source, a.status "
                    + "FROM appointment a LEFT JOIN store s ON s.store_code = a.store_code "
                    + "WHERE a.customer_id = ? ORDER BY a.appt_date DESC, a.appt_time DESC, a.appt_no DESC",
                    customerId);
            for (Map<String, Object> row : rows) {
                items.add(toCItem(row));
            }
        }
        return ResponseEntity.ok(ok(items));
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
        String storeName = str(body.get("storeName"));
        String project = str(body.get("project"));
        String timeSlot = str(body.get("timeSlot"));
        if (storeName.isEmpty()) {
            return ResponseEntity.badRequest().body(err(400, "请选择预约门店"));
        }
        if (project.isEmpty()) {
            return ResponseEntity.badRequest().body(err(400, "请选择预约项目"));
        }
        Matcher m = TIME_SLOT.matcher(timeSlot);
        if (!m.matches()) {
            return ResponseEntity.badRequest().body(err(400, "预约时间格式不正确（应为 yyyy-MM-ddTHH:mm）"));
        }
        String apptDate = m.group(1);
        String apptTime = m.group(2);
        List<Map<String, Object>> stores = jdbcTemplate.queryForList(
                "SELECT store_code FROM store WHERE store_name = ? AND status = '营业中'", storeName);
        if (stores.isEmpty()) {
            return ResponseEntity.badRequest().body(err(400, "门店不存在或已停业: " + storeName));
        }
        String storeCode = String.valueOf(stores.get(0).get("store_code"));

        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("customerId", customerId);
        cmd.put("storeCode", storeCode);
        cmd.put("project", project);
        cmd.put("apptDate", apptDate);
        cmd.put("apptTime", apptTime);
        cmd.put("source", "C端小程序");
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    txnServiceUrl + "/api/txn/internal/c-appointments",
                    new HttpEntity<>(cmd, internalHeaders()), Map.class);
            Map<String, Object> view = resp.getBody();
            return ResponseEntity.ok(ok(view == null ? Map.of() : viewToCItem(view)));
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(err(e.getStatusCode().value(), extractMessage(e, "预约创建失败，请稍后重试")));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err(502, "预约服务暂不可用，请稍后重试"));
        }
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable("id") String id, HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT customer_id FROM appointment WHERE appt_no = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(404, "预约不存在或无权查看"));
        }
        if (!customerId.equals(rows.get(0).get("customer_id"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "预约不存在或无权操作"));
        }
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    txnServiceUrl + "/api/txn/internal/c-appointments/" + id + "/cancel",
                    new HttpEntity<>(Map.of(), internalHeaders()), Map.class);
            Map<String, Object> view = resp.getBody();
            return ResponseEntity.ok(ok(view == null ? Map.of() : viewToCItem(view)));
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(err(e.getStatusCode().value(), extractMessage(e, "预约取消失败，请稍后重试")));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err(502, "预约服务暂不可用，请稍后重试"));
        }
    }

    /** 登录守卫（照 CBrowseController profile 同口径：未登录 401/凭证无效 401/停用 403 全中文）。 */
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

    /** B 端 appointment.status 四态 → C 端 ApptStatus 六态（无对应态如实不映射）。 */
    private static String toCStatus(String bStatus) {
        return switch (bStatus) {
            case "已预约" -> "NEW";
            case "已到店" -> "ARRIVED";
            case "未到诊" -> "NO_SHOW";
            case "已取消" -> "CANCELLED";
            default -> "NEW";
        };
    }

    /** 列表行投影（JdbcTemplate 行 → 前端 Appointment 契约）。 */
    private static Map<String, Object> toCItem(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.get("appt_no"));
        item.put("customerId", row.get("customer_id"));
        item.put("storeCode", row.get("store_code"));
        item.put("storeName", row.get("store_name"));
        item.put("project", row.get("project"));
        item.put("timeSlot", row.get("appt_date") + "T" + row.get("appt_time") + ":00");
        item.put("status", toCStatus(String.valueOf(row.get("status"))));
        item.put("source", row.get("source"));
        return item;
    }

    /** txn internal AppointmentView 投影（创建/取消响应 → 前端 Appointment 契约）。 */
    private static Map<String, Object> viewToCItem(Map<String, Object> view) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", view.get("apptNo"));
        item.put("customerId", view.get("customerId"));
        item.put("storeCode", view.get("storeCode"));
        item.put("storeName", view.get("storeName"));
        item.put("project", view.get("project"));
        item.put("timeSlot", view.get("apptDate") + "T" + view.get("apptTime") + ":00");
        item.put("status", toCStatus(String.valueOf(view.get("status"))));
        item.put("source", view.get("source"));
        return item;
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
