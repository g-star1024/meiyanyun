package com.meiyun.security;

/**
 * 当前登录用户上下文（请求级 ThreadLocal）。
 * 鉴权拦截器 preHandle 写入、afterCompletion 清理，业务代码可经 {@link #currentUser()} 取当前操作人。
 */
public final class SecurityContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private SecurityContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 当前工号；未登录（公共接口）返回 null。 */
    public static String currentStaffId() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.staffId();
    }

    /** 当前姓名；未登录返回 null。 */
    public static String currentStaffName() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.staffName();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
