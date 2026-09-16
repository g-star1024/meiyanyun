package com.meiyun.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 审计写入边界统一收敛（各服务 RestAuditRecorder 在调用 audit-service 前使用）。
 *
 * <p>超管代操作（impersonate）时：
 * <ul>
 *   <li>审计 actor 强制改写为 realSub（真实操作超管），忽略业务代码传入的 sub（被切换人），
 *       杜绝业务侧用 {@link DataScope#currentActor()} 误记被切换人；</li>
 *   <li>payload jsonb 服务端注入 act（被切换人工号）/ realSub（真实超管），前端/业务体不可信无法伪造。</li>
 * </ul>
 * 非代操作态原样透传。payload 非合法 JSON 对象时退化为包装对象，保证审计写入永不因载荷格式中断。
 */
public final class AuditBoundary {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditBoundary() {
    }

    /** 收敛后的审计 actor：代操作态返回真实超管 realSub，否则返回传入 actor（空回落 system）。 */
    public static String resolveActor(String passedActor) {
        String real = DataScope.currentRealActor();
        if (DataScope.impersonating()) {
            return real;
        }
        return (passedActor == null || passedActor.isBlank()) ? real : passedActor;
    }

    /** 向审计 payload 注入代操作双标记；非代操作态原样返回。 */
    public static String enrichPayload(String payload) {
        if (!DataScope.impersonating()) {
            return payload;
        }
        try {
            JsonNode node = MAPPER.readTree(payload == null || payload.isBlank() ? "{}" : payload);
            if (node.isObject()) {
                ObjectNode obj = (ObjectNode) node;
                LoginUser u = SecurityContext.get();
                if (u != null) {
                    obj.put("act", u.act());
                    obj.put("realSub", u.realSub());
                }
                return MAPPER.writeValueAsString(obj);
            }
        } catch (Exception ignored) {
            // 落到包装兜底
        }
        try {
            ObjectNode wrap = MAPPER.createObjectNode();
            wrap.put("raw", payload);
            LoginUser u = SecurityContext.get();
            if (u != null) {
                wrap.put("act", u.act());
                wrap.put("realSub", u.realSub());
            }
            return MAPPER.writeValueAsString(wrap);
        } catch (Exception e) {
            throw new IllegalStateException("审计 payload 序列化失败", e);
        }
    }
}
