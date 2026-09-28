package com.meiyun.c.auth;

import com.meiyun.c.audit.CAuditRecorder;
import com.meiyun.c.config.CProps;
import com.meiyun.c.domain.CMemberAuth;
import com.meiyun.c.domain.CMemberAuthRepository;
import com.meiyun.c.security.CAuthFilter;
import com.meiyun.c.security.CJwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端认证与配置端点（DESIGN-C §二 #1-#4）：
 * #1 POST /api/c/auth/wechat-login —— 微信 code2session；凭证未配置（appid/secret 空）如实 502；
 * #2 POST /api/c/auth/dev-login —— 开发期手机号快捷登录；dev-login-enabled=false 时 404 隐身（区别于 B 端 403）；
 * #3 GET  /api/c/mp/config      —— RemoteConfig 八字段（前端 mp-uniapp/src/api/config.ts 精确契约）；
 * #4 GET  /api/c/auth/me        —— 当前会员：未登录 401；已登录 200（customer_id 已绑定时只读投影 customer 表）。
 * 全部响应包约 {code,message,data}（CWebAdvice 统一包裹），错误信息全中文（铁律 3）。
 */
@RestController
@RequestMapping("/api/c")
public class AuthController {

    private final CProps props;
    private final CJwtUtil jwtUtil;
    private final CMemberAuthRepository memberAuthRepository;
    private final CAuditRecorder auditRecorder;
    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;

    public AuthController(CProps props, CJwtUtil jwtUtil, CMemberAuthRepository memberAuthRepository,
                          CAuditRecorder auditRecorder, RestTemplate restTemplate, JdbcTemplate jdbcTemplate) {
        this.props = props;
        this.jwtUtil = jwtUtil;
        this.memberAuthRepository = memberAuthRepository;
        this.auditRecorder = auditRecorder;
        this.restTemplate = restTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record WechatLoginReq(@NotBlank(message = "登录凭证 code 不能为空") String code) {
    }

    public record DevLoginReq(@NotBlank(message = "手机号不能为空") String phone) {
    }

    /** #1 微信小程序登录：凭证未配置→502 如实；code2session 失败→502 如实；成功→查/建会员＋签 token＋审计 */
    @PostMapping("/auth/wechat-login")
    public ResponseEntity<Map<String, Object>> wechatLogin(@RequestBody WechatLoginReq req) {
        String appid = props.getWechat().getAppid();
        String secret = props.getWechat().getSecret();
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err(502, "微信小程序凭证未配置，请联系门店管理员"));
        }
        Map<String, Object> session;
        try {
            String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + appid
                    + "&secret=" + secret + "&js_code=" + req.code()
                    + "&grant_type=authorization_code";
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            if (resp == null || resp.get("openid") == null) {
                Object errcode = resp == null ? null : resp.get("errcode");
                Object errmsg = resp == null ? null : resp.get("errmsg");
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(err(502, "微信登录服务返回异常（errcode=" + errcode + "，" + errmsg + "）"));
            }
            session = resp;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(err(502, "微信登录服务暂不可用，请稍后重试"));
        }
        String openid = String.valueOf(session.get("openid"));
        Object unionid = session.get("unionid");
        CMemberAuth member = memberAuthRepository.findByOpenid(openid).orElseGet(() -> {
            CMemberAuth m = new CMemberAuth();
            m.setOpenid(openid);
            m.setStatus("ACTIVE");
            return m;
        });
        if (unionid != null) {
            member.setUnionid(String.valueOf(unionid));
        }
        if ("DISABLED".equals(member.getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号已停用，请联系门店"));
        }
        member.setLastLoginAt(OffsetDateTime.now());
        member.setUpdatedAt(OffsetDateTime.now());
        member = memberAuthRepository.save(member);
        String token = jwtUtil.issue(member.getOpenid(), member.getCustomerId(), false);
        auditRecorder.recordLogin(member.getOpenid(), false, "微信小程序登录");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("bound", member.getCustomerId() != null && !member.getCustomerId().isBlank());
        data.put("nickname", member.getNickname());
        data.put("avatar", member.getAvatar());
        return ResponseEntity.ok(ok(data));
    }

    /** #2 开发期快捷登录：开关关闭→404 隐身；开启→openid=dev_<phone> 查/建，幂等 */
    @PostMapping("/auth/dev-login")
    public ResponseEntity<Map<String, Object>> devLogin(@RequestBody DevLoginReq req) {
        if (!props.isDevLoginEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(404, "接口不存在"));
        }
        String openid = "dev_" + req.phone();
        CMemberAuth member = memberAuthRepository.findByOpenid(openid).orElseGet(() -> {
            CMemberAuth m = new CMemberAuth();
            m.setOpenid(openid);
            m.setPhone(req.phone());
            m.setNickname("体验会员");
            m.setStatus("ACTIVE");
            return m;
        });
        if ("DISABLED".equals(member.getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err(403, "账号已停用，请联系门店"));
        }
        member.setLastLoginAt(OffsetDateTime.now());
        member.setUpdatedAt(OffsetDateTime.now());
        member = memberAuthRepository.save(member);
        String token = jwtUtil.issue(member.getOpenid(), member.getCustomerId(), true);
        auditRecorder.recordLogin(member.getOpenid(), true, "开发期快捷登录");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("bound", member.getCustomerId() != null && !member.getCustomerId().isBlank());
        data.put("nickname", member.getNickname());
        data.put("avatar", member.getAvatar());
        return ResponseEntity.ok(ok(data));
    }

    /** #3 小程序展示配置：RemoteConfig 八字段精确结构（前端 config.ts 契约），免登 */
    @GetMapping("/mp/config")
    public Map<String, Object> mpConfig() {
        CProps.Brand brand = props.getBrand();
        String appid = props.getWechat().getAppid();
        String masked = (appid == null || appid.length() < 8) ? ""
                : appid.substring(0, 4) + "****" + appid.substring(appid.length() - 4);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("brandName", brand.getBrandName());
        data.put("servicePhone", brand.getServicePhone());
        data.put("wechatPayEnabled", props.getWechat().isPayEnabled());
        data.put("pointsMallEnabled", brand.isPointsMallEnabled());
        data.put("inviteEnabled", brand.isInviteEnabled());
        data.put("themeColor", brand.getThemeColor());
        data.put("notice", brand.getNotice());
        data.put("weappAppIdMasked", masked);
        return ok(data);
    }

    /** #4 当前会员：C token 已由 CAuthFilter 验签；customer_id 已绑定时只读投影 customer 表 */
    @GetMapping("/auth/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
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
        data.put("openid", member.getOpenid());
        data.put("phone", member.getPhone());
        data.put("nickname", member.getNickname());
        data.put("avatar", member.getAvatar());
        data.put("bound", member.getCustomerId() != null && !member.getCustomerId().isBlank());
        if (member.getCustomerId() != null && !member.getCustomerId().isBlank()) {
            data.put("customerId", member.getCustomerId());
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT name, phone, level FROM customer WHERE customer_id = ?", member.getCustomerId());
            if (!rows.isEmpty()) {
                Map<String, Object> customer = new LinkedHashMap<>();
                customer.put("name", rows.get(0).get("name"));
                customer.put("phone", rows.get(0).get("phone"));
                customer.put("level", rows.get(0).get("level"));
                data.put("customer", customer);
            }
        }
        return ResponseEntity.ok(ok(data));
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
