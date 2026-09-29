package com.meiyun.c.engage;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端会员互动域端点（DESIGN-C §四端点 #9，C-B6）：
 * GET /api/c/advisor —— 专属顾问：customer.owner_staff_id→staff 只读投影（staff_name/role_code
 *   中文映射），avatar=姓名首字；未分配顾问/档案不可读如实 data=null（前端空态「专属顾问待分配」）；
 *   years/tags/served/rating 无源字段如实 null（DESIGN-C §7 登记），faqs 静态文案留前端常量。
 * GET /api/c/invite —— 邀请有礼：邀请码=本人 customer_id（随拍定）；统计三值全源——
 *   invited=referral 本人推荐总数、visited=status IN (VISITED,DEAL) 到店数、
 *   points=referral_reward 本人受益 GRANTED 积分合计；三档奖励规则文案无数据源（referral_campaign
 *   无奖励规则字段）留前端常量，§7 登记。
 * 既有表零改动契约：customer/staff/referral/referral_reward 全部 JdbcTemplate 只读 SELECT。
 */
@RestController
@RequestMapping("/api/c")
public class CEngageController {

    private final CMemberAuthRepository memberAuthRepository;
    private final JdbcTemplate jdbcTemplate;

    public CEngageController(CMemberAuthRepository memberAuthRepository, JdbcTemplate jdbcTemplate) {
        this.memberAuthRepository = memberAuthRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/advisor")
    public ResponseEntity<Map<String, Object>> advisor(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.staff_name, s.role_code FROM customer c "
                + "JOIN staff s ON s.staff_id = c.owner_staff_id "
                + "WHERE c.customer_id = ? AND c.merged_into IS NULL AND c.anonymized_at IS NULL "
                + "AND s.status = '在职'",
                customerId);
        if (rows.isEmpty()) {
            return ResponseEntity.ok(ok(null));
        }
        Map<String, Object> row = rows.get(0);
        String name = row.get("staff_name") == null ? "" : String.valueOf(row.get("staff_name"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("title", roleText(row.get("role_code") == null ? null : String.valueOf(row.get("role_code"))));
        data.put("avatar", name.isEmpty() ? "顾" : name.substring(0, 1));
        data.put("years", null);
        data.put("tags", List.of());
        data.put("served", null);
        data.put("rating", null);
        return ResponseEntity.ok(ok(data));
    }

    @GetMapping("/invite")
    public ResponseEntity<Map<String, Object>> invite(HttpServletRequest request) {
        Guard g = guard(request);
        if (g.error() != null) {
            return g.error();
        }
        String customerId = g.member().getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        Long invited = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral WHERE referrer_customer_id = ?", Long.class, customerId);
        Long visited = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral WHERE referrer_customer_id = ? AND status IN ('VISITED','DEAL')",
                Long.class, customerId);
        Long points = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(points), 0) FROM referral_reward "
                + "WHERE beneficiary_customer_id = ? AND status = 'GRANTED' AND reward_type = 'POINT'",
                Long.class, customerId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", customerId);
        data.put("invited", invited == null ? 0 : invited);
        data.put("visited", visited == null ? 0 : visited);
        data.put("points", points == null ? 0 : points);
        return ResponseEntity.ok(ok(data));
    }

    private static String roleText(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return "美容顾问";
        }
        return switch (roleCode) {
            case "CONSULTANT" -> "美容顾问";
            case "DOCTOR" -> "医师";
            case "NURSE" -> "护士";
            case "MANAGER" -> "店长";
            default -> roleCode;
        };
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
