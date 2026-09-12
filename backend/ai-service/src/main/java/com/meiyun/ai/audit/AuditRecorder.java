package com.meiyun.ai.audit;

/**
 * 审计记录器（对接 audit-service 的 append-only 接口）。
 * AI 平台供应商/模型/绑定/审批全部写动作经此落审计链；payload 必须为合法 JSON 字符串（audit_log.payload 为 jsonb）。
 */
public interface AuditRecorder {
    void record(String bizType, String txnNo, String actor, String action, String payload);
}
