package com.meiyun.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一鉴权拦截器（F3 卡1 方案 A 身份维收口）：
 * 1. /internal/ 伞下（/api/&lt;service&gt;/internal/**，与网关 router.go isInternalPath 同口径）
 *    仅接受 X-Internal-Token 系统身份：匹配 → system（"*" 全权限）；不匹配 →
 *    无 Bearer 401 / 有 Bearer（员工 JWT，含超管）403，人类身份一律不得调服务间端点；
 * 2. 非 internal 路径：X-Internal-Token 一律忽略（不再授予系统身份），仅从
 *    Authorization: Bearer &lt;token&gt; 解析 JWT，写入 {@link SecurityContext}；
 * 3. 方法/类上有 {@link RequirePerm} 时校验权限码，无 token → 401，有权限不足 → 403；
 * 4. 公共路径（public-paths，如 /api/org/auth/login）与 OPTIONS 预检直接放行；
 * 5. 请求线程归还线程池前一律清理 SecurityContext（同步 afterCompletion；异步 afterConcurrentHandlingStarted；
 *    403 拒绝后即时清理），杜绝 ThreadLocal 身份残留串给后续复用线程的请求。
 */
public class AuthInterceptor implements AsyncHandlerInterceptor {

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
        boolean tokenMatch = internalToken != null && !internalToken.isBlank()
                && constantTimeEquals(internalToken, props.getInternalToken());
        if (isInternalPath(request.getRequestURI())) {
            // 方案 A①：伞下仅系统 token 身份可调；不匹配即拒（无 Bearer 401 / 员工 JWT 403），
            // 不再回落 JWT 链——员工（含超管）直连服务间端点属越权场景，一律不放行。
            if (!tokenMatch) {
                String auth = request.getHeader("Authorization");
                boolean hasBearer = auth != null && auth.startsWith("Bearer ");
                writeError(response,
                        hasBearer ? HttpServletResponse.SC_FORBIDDEN : HttpServletResponse.SC_UNAUTHORIZED,
                        hasBearer ? "服务间内部端点不接受员工身份调用" : "服务间内部端点仅接受系统身份调用");
                return false;
            }
            user = new LoginUser("system", "系统服务", List.of(), null,
                    "GROUP", List.of("*"), false, null, List.of());
        } else {
            // 方案 A②：非伞下路径忽略 X-Internal-Token（不再授予系统身份），只认员工 JWT。
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
                SecurityContext.clear();
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

    /**
     * 异步（SSE 等）请求专用清理：异步处理一旦启动，请求线程即刻归还 Tomcat 线程池，
     * afterCompletion 延后到异步结束才在其他线程执行；若不在此清理，ThreadLocal 身份将
     * 残留在线程上串给后续复用该线程的请求（B49 卡7 三轨真验抓到的越权/串身份 P0）。
     */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        SecurityContext.clear();
    }

    /**
     * /internal/ 伞下判定（与网关 router.go isInternalPath 同口径）：
     * /api/&lt;service&gt;/internal 或 /api/&lt;service&gt;/internal/**。九服务均无 context-path，
     * getRequestURI() 即应用内路径。
     */
    private boolean isInternalPath(String uri) {
        if (uri == null || !uri.startsWith("/api/")) {
            return false;
        }
        String rest = uri.substring("/api/".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String sub = rest.substring(slash + 1);
        return sub.equals("internal") || sub.startsWith("internal/");
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
