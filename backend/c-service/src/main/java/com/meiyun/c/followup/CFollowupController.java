package com.meiyun.c.followup;

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

/**
 * C 端术后回访域端点（DESIGN-C §四端点 #8，C-B5）：
 * GET  /api/c/followups —— 本人回访列表：followup 行级隔离只读投影（customer_id 取登录态
 *   绑定档案），status 与前端同词（PENDING/DONE/SKIPPED，零映射），按 plan_date 倒序。
 * POST /api/c/followups/{id}/submit —— 客户自评提交：满意度 1-5 前置校验，经 RestTemplate
 *   调 txn internal 端点落库（归属双保险＋非 PENDING 幂等直返＋followupByName=「客户自评」＋
 *   不良反应转 OPEN 跟进）；txn 中文错误原码原话透传，网络异常 502 兜底。
 * 既有表零改动契约：followup 只读 SELECT，写操作一律经 txn internal 端点落库
 * （DESIGN-C L15/L71）。
 */
@RestController
@RequestMapping("/api/c/followups")
public class CFollowupController {

    private static final ObjectMapper OM = new ObjectMapper();

    private final CMemberAuthRepository memberAuthRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final CProps props;

    @Value("${meiyun.txn.service-url:http://127.0.0.1:8083}")
    private String txnServiceUrl;

    public CFollowupController(CMemberAuthRepository memberAuthRepository, JdbcTemplate jdbcTemplate,
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
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, followup_no, customer_name, project, plan_date, status, "
                + "satisfaction, note, done_at "
                + "FROM followup WHERE customer_id = ? ORDER BY plan_date DESC, id DESC",
                customerId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(row.get("id")));
            item.put("followupNo", row.get("followup_no"));
            item.put("customerName", row.get("customer_name"));
            item.put("project", row.get("project"));
            item.put("planDate", row.get("plan_date") == null ? "" : String.valueOf(row.get("plan_date")));
            item.put("status", row.get("status"));
            Object sat = row.get("satisfaction");
            item.put("satisfaction", sat instanceof Number n ? n.intValue() : null);
            item.put("note", row.get("note"));
            item.put("doneAt", row.get("done_at") == null ? null : String.valueOf(row.get("done_at")));
            items.add(item);
        }
        return ResponseEntity.ok(ok(items));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable("id") String id, HttpServletRequest request,
                                                      @RequestBody Map<String, Object> body) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        if (!id.matches("\\d+")) {
            return ResponseEntity.badRequest().body(err(400, "随访编号不正确"));
        }
        int satisfaction = 0;
        String satRaw = str(body.get("satisfaction"));
        if (!satRaw.isEmpty()) {
            try {
                satisfaction = Integer.parseInt(satRaw);
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(err(400, "满意度评分不正确"));
            }
        }
        if (satisfaction < 1 || satisfaction > 5) {
            return ResponseEntity.badRequest().body(err(400, "请为本次恢复情况评分（1-5 星）"));
        }
        boolean adverse = "true".equalsIgnoreCase(str(body.get("adverseReaction")))
                || Boolean.TRUE.equals(body.get("adverseReaction"));
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("customerId", customerId);
        cmd.put("satisfaction", satisfaction);
        cmd.put("note", str(body.get("note")));
        cmd.put("adverseReaction", adverse);
        cmd.put("adverseNote", str(body.get("adverseNote")));
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    txnServiceUrl + "/api/txn/internal/c-followups/" + id + "/submit",
                    new HttpEntity<>(cmd, internalHeaders()), Map.class);
            Map<String, Object> view = resp.getBody();
            return ResponseEntity.ok(ok(view == null ? Map.of() : view));
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(err(e.getStatusCode().value(), extractMessage(e, "回访提交失败，请稍后重试")));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err(502, "随访服务暂不可用，请稍后重试"));
        }
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
