-- ================================================================
-- V71 · T3 数据中台 连接器调用日志表（T3-B1 · DESIGN-T3 §三）
-- 属主 org-service（V70 之后顺号）；铁律0：仅新增 integration_call_log；既有表零改动
-- uk(connector_id, transaction_id) 幂等锚：transaction_id 全程唯一（测试 TEST-{id}-{ts} / 同步 txn_no / 重试复用）
-- ================================================================

CREATE TABLE IF NOT EXISTS integration_call_log (
    id             BIGSERIAL    PRIMARY KEY,
    connector_id   BIGINT       NOT NULL REFERENCES integration_connector(id),
    transaction_id VARCHAR(64)  NOT NULL,
    direction      VARCHAR(4)   NOT NULL DEFAULT 'OUT',
    method         VARCHAR(8),
    endpoint       VARCHAR(256),
    status_code    INTEGER,
    latency_ms     INTEGER,
    status         VARCHAR(8)   NOT NULL,
    error_msg      VARCHAR(256),
    request_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_integration_call_log_txn    UNIQUE (connector_id, transaction_id),
    CONSTRAINT chk_integration_call_log_dir   CHECK (direction IN ('OUT','IN')),
    CONSTRAINT chk_integration_call_log_status CHECK (status IN ('SENT','ACK','FAIL'))
);

COMMENT ON TABLE  integration_call_log                IS 'T3 数据中台 连接器调用日志（OUT=出站；IN 方向留 §7 移交登记）';
COMMENT ON COLUMN integration_call_log.connector_id   IS '连接器 id（FK→integration_connector）';
COMMENT ON COLUMN integration_call_log.transaction_id IS '幂等键（测试=TEST-{id}-{ts}；同步=txn_no）';
COMMENT ON COLUMN integration_call_log.direction      IS 'OUT=出站调用 / IN=三方回调（二期）';
COMMENT ON COLUMN integration_call_log.method         IS 'HTTP 方法';
COMMENT ON COLUMN integration_call_log.endpoint       IS '调用路径/地址';
COMMENT ON COLUMN integration_call_log.status_code    IS 'HTTP 状态码（网络异常=0）';
COMMENT ON COLUMN integration_call_log.latency_ms     IS '耗时（毫秒）';
COMMENT ON COLUMN integration_call_log.status         IS 'SENT=已发送 / ACK=三方已确认 / FAIL=失败';
COMMENT ON COLUMN integration_call_log.error_msg      IS '失败如实文案';
COMMENT ON COLUMN integration_call_log.request_at     IS '调用时间';

CREATE INDEX IF NOT EXISTS idx_integration_call_log_conn_time ON integration_call_log (connector_id, request_at DESC);
