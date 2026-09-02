package com.meiyun.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一鉴权拦截器：
 * 1. 从 Authorization: Bearer &lt;token&gt; 解析 JWT，写入 {@link SecurityContext}；
 * 2. 方法/类上有 {@link RequirePerm} 时校验权限码，无 token → 401，有权限不足 → 403；
 * 3. 公共路径（public-paths，如 /api/org/auth/login）与 OPTIONS 预检直接放行。
 */
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtTokenUtil jwt;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthInterceptor(JwtTokenUtil jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        LoginUser user = null;
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
