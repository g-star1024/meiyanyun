package com.meiyun.txn.audit;

/**
 * 审计记录器（对接 audit-service 的 append-only 接口）。
 * 交易域所有双签/资金动作都经此落审计链。
 */
public interface AuditRecorder {
    void record(String bizType, String txnNo, String actor, String action, String payload);
}
