package com.meiyun.c.security;

import com.meiyun.c.config.CProps;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * C 端认证过滤器（B/C 隔离红线：不复用 B 端 AuthInterceptor）：
 * ① X-Tenant-Id 缺失或不等于 meiyun.c.tenant-id → 403 中文包约；
 * ② 免登白名单（POST /api/c/auth/wechat-login、POST /api/c/auth/dev-login、GET /api/c/mp/config）放行；
 * ③ 其余 /api/c/** 必须携带合法 C 端 Bearer token，失败 → 401 中文包约；
 * ④ 验签成功则 openid/customerId 塞 request attribute 供控制器取用。
 * 响应包约与 mp-uniapp/src/utils/request.ts 对齐：{code,message,data}，401 触发前端 clearToken。
 */
@Component
public class CAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_OPENID = "C_OPENID";
    public static final String ATTR_CUSTOMER_ID = "C_CUSTOMER_ID";

    private final CProps props;
    private final CJwtUtil jwtUtil;

    public CAuthFilter(CProps props, CJwtUtil jwtUtil) {
        this.props = props;
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/c/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenant = request.getHeader("X-Tenant-Id");
        if (tenant == null || !tenant.equals(props.getTenantId())) {
            writeJson(response, 403, "租户标识缺失或不正确");
            return;
        }
        if (isPublic(request)) {
            chain.doFilter(request, response);
            return;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeJson(response, 401, "请先登录");
            return;
        }
        try {
            Map<String, Object> claims = jwtUtil.parse(auth.substring(7));
            Object sub = claims.get("sub");
            if (sub == null || String.valueOf(sub).isEmpty()) {
                writeJson(response, 401, "请先登录");
                return;
            }
            request.setAttribute(ATTR_OPENID, String.valueOf(sub));
            Object customerId = claims.get("customerId");
            if (customerId != null) {
                request.setAttribute(ATTR_CUSTOMER_ID, String.valueOf(customerId));
            }
            chain.doFilter(request, response);
        } catch (CJwtUtil.CAuthException e) {
            writeJson(response, 401, e.getMessage());
        }
    }

    private boolean isPublic(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        if ("GET".equals(method) && "/api/c/mp/config".equals(uri)) {
            return true;
        }
        if ("POST".equals(method) && "/api/c/auth/wechat-login".equals(uri)) {
            return true;
        }
        return "POST".equals(method) && "/api/c/auth/dev-login".equals(uri);
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
