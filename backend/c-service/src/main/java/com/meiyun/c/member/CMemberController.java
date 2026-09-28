package com.meiyun.c.member;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端会员卡域端点（DESIGN-C §四端点 #8，C-B5）：
 * GET /api/c/member/cards —— 本人卡包：member_card 行级隔离只读投影，次卡下发
 *   totalTimes/remainTimes，储值卡 balance/giftBalance 分→元；卡状态原值下发
 *   （在用/退卡中/已退卡/已用完），有效期 expires_at 原值（空=长期有效，前端如实展示）。
 * 既有表零改动契约：member_card 只读 SELECT（照 CBrowseController 先例，不落实体不触发 ddl）。
 * 行级隔离：customer_id 取登录态绑定档案，未绑定 403 中文。
 */
@RestController
@RequestMapping("/api/c/member")
public class CMemberController {

    private final CMemberAuthRepository memberAuthRepository;
    private final JdbcTemplate jdbcTemplate;

    public CMemberController(CMemberAuthRepository memberAuthRepository, JdbcTemplate jdbcTemplate) {
        this.memberAuthRepository = memberAuthRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/cards")
    public ResponseEntity<Map<String, Object>> cards(HttpServletRequest request) {
        Object openidAttr = request.getAttribute(CAuthFilter.ATTR_OPENID);
        if (openidAttr == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err(401, "请先登录"));
        }
        CMemberAuth member = memberAuthRepository.findByOpenid(String.valueOf(openidAttr)).orElse(null);
        if (member == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err(401, "登录凭证无效或已过期"));
        }
        if ("DISABLED".equals(member.getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号已停用，请联系门店"));
        }
        String customerId = member.getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号未绑定会员档案，请先联系门店建档"));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT card_no, card_item, card_type, total_times, remain_times, "
                + "balance, gift_balance, status, expires_at "
                + "FROM member_card WHERE customer_id = ? ORDER BY created_at DESC",
                customerId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cardNo", row.get("card_no"));
            item.put("name", row.get("card_item"));
            item.put("cardType", row.get("card_type"));
            item.put("totalTimes", row.get("total_times") == null ? 0 : ((Number) row.get("total_times")).intValue());
            item.put("remainTimes", row.get("remain_times") == null ? 0 : ((Number) row.get("remain_times")).intValue());
            item.put("balance", fenToYuan(num(row.get("balance"))));
            item.put("giftBalance", fenToYuan(num(row.get("gift_balance"))));
            item.put("status", row.get("status"));
            item.put("expire", row.get("expires_at") == null ? "" : String.valueOf(row.get("expires_at")));
            items.add(item);
        }
        return ResponseEntity.ok(ok(items));
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
