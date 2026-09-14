-- B48 卡1：审计写失败补偿 outbox
-- 各业务服务审计直发失败时，与业务同事务 INSERT 本表；audit-service 中继 Job 回投 audit_log 并置 SENT。
-- 重试 5 次仍失败置 DEAD（GET /api/audit/outbox/stats 监测，POST /api/audit/outbox/{id}/retry 人工重投）。
-- payload 用 TEXT 而非 JSONB：兼容历史纯文本 payload，回投时由 AuditService canonicalize 后写入 jsonb。
CREATE TABLE IF NOT EXISTS audit_outbox (
    id             BIGSERIAL PRIMARY KEY,
    source_service VARCHAR(32)  NOT NULL,
    biz_type       VARCHAR(32)  NOT NULL,
    txn_no         VARCHAR(128),
    actor          VARCHAR(32)  NOT NULL,
    action         VARCHAR(32)  NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING','SENT','DEAD')),
    retry_count    INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(500),
    audit_id       BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_audit_outbox_status_id ON audit_outbox (status, id);
