package com.meiyun.marketing.audit;

/**
 * 审计记录器（对接 audit-service 的 append-only 接口）。
 * 营销域券/活动全部写动作经此落审计链；payload 必须为合法 JSON 字符串（audit_log.payload 为 jsonb）。
 */
public interface AuditRecorder {
    void record(String bizType, String txnNo, String actor, String action, String payload);
}
