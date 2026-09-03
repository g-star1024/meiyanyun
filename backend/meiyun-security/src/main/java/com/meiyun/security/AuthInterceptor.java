package com.meiyun.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一鉴权拦截器：
 * 1. 服务间内部调用携带 X-Internal-Token 且与配置一致时，按系统身份（"*" 全权限）放行；
 * 2. 否则从 Authorization: Bearer &lt;token&gt; 解析 JWT，写入 {@link SecurityContext}；
 * 3. 方法/类上有 {@link RequirePerm} 时校验权限码，无 token → 401，有权限不足 → 403；
 * 4. 公共路径（public-paths，如 /api/org/auth/login）与 OPTIONS 预检直接放行。
 */
public class AuthInterceptor implements HandlerInterceptor {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final JwtTokenUtil jwt;
    private final SecurityProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthInterceptor(JwtTokenUtil jwt, SecurityProperties props) {
        this.jwt = jwt;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        LoginUser user = null;
        String internalToken = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (internalToken != null && !internalToken.isBlank()
                && constantTimeEquals(internalToken, props.getInternalToken())) {
            user = new LoginUser("system", "系统服务", List.of(), null,
                    "GROUP", List.of("*"), false, null, List.of());
        }
        if (user == null) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String token = auth.substring(7).trim();
                try {
                    user = jwt.parse(token);
                } catch (JwtTokenUtil.JwtAuthException e) {
                    writeError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
                    return false;
                }
            }
        }
        if (user != null) {
            SecurityContext.set(user);
        }

        if (handler instanceof HandlerMethod hm) {
            RequirePerm require = hm.getMethodAnnotation(RequirePerm.class);
            if (require == null) {
                require = hm.getBeanType().getAnnotation(RequirePerm.class);
            }
            if (require != null) {
                if (user == null) {
                    writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已失效，请重新登录");
                    return false;
                }
                for (String perm : require.value()) {
                    if (user.hasPerm(perm)) {
                        return true;
                    }
                }
                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                        "无操作权限：需要 " + String.join(" 或 ", require.value()));
                return false;
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        SecurityContext.clear();
    }

    /** 令牌常量时间比较，避免时序侧信道；长度不等直接不等（长度本身非敏感）。 */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status);
        body.put("message", message);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
