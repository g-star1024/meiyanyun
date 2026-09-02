package com.meiyun.customer.audit;

/**
 * 审计记录器（对接 audit-service 的 append-only 接口）。
 * 客户域字典/资料等管理动作经此落审计链。
 */
public interface AuditRecorder {
    void record(String bizType, String txnNo, String actor, String action, String payload);
}
