package com.meiyun.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RBAC 鉴权注解：标注在 Controller 方法或类上，声明所需权限码。
 * 权限码格式 {@code 资源:动作}（如 {@code cashier:sign}、{@code refund:approve}）。
 * 多值为「任一满足」即可（OR 语义）；需要全部满足时叠加多个注解暂不支持（MVP 无场景）。
 * 超管通配 {@code *} 由 JWT perms 携带，拦截器直接放行。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    /** 所需权限码，任一命中即放行。 */
    String[] value();
}
