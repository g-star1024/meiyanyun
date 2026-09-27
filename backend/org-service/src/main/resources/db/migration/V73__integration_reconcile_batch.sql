-- ================================================================
-- V73 · T3 数据中台 T+1 对账批次表（T3-B2 · DESIGN-T3 §三）
-- 属主 org-service（V72 之后顺号）；铁律0：仅新增 integration_reconcile_batch；既有表零改动
-- uk(connector_id, biz_date) 幂等锚：重放返既有批次；connector_id=0=全部连接器汇总（避开 NULL 不参与 uk）
-- 本地口径：matched=remote_ack 置 MATCHED；long/short/diff 恒 0 如实（三方账单文件导入留 §7）
-- batch_no = REC-yyyyMMdd-seq（库内 maxSeqOfDay 照 B95 D7 先例）
-- ================================================================

CREATE TABLE IF NOT EXISTS integration_reconcile_batch (
    id            BIGSERIAL    PRIMARY KEY,
    batch_no      VARCHAR(40)  NOT NULL,
    connector_id  BIGINT       NOT NULL DEFAULT 0,
    biz_date      DATE         NOT NULL,
    total_count   INTEGER      NOT NULL DEFAULT 0,
    matched_count INTEGER      NOT NULL DEFAULT 0,
    pending_count INTEGER      NOT NULL DEFAULT 0,
    long_count    INTEGER      NOT NULL DEFAULT 0,
    short_count   INTEGER      NOT NULL DEFAULT 0,
    failed_count  INTEGER      NOT NULL DEFAULT 0,
    total_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    diff_amount   NUMERIC(14,2) NOT NULL DEFAULT 0,
    status        VARCHAR(8)   NOT NULL DEFAULT 'RUNNING',
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    CONSTRAINT uq_integration_reconcile_batch_no    UNIQUE (batch_no),
    CONSTRAINT uq_integration_reconcile_batch_scope UNIQUE (connector_id, biz_date),
    CONSTRAINT chk_integration_reconcile_batch_status CHECK (status IN ('RUNNING','DONE','FAILED'))
);

COMMENT ON TABLE  integration_reconcile_batch              IS 'T3 数据中台 T+1 对账批次（本地口径；三方账单文件导入解析留 §7 移交）';
COMMENT ON COLUMN integration_reconcile_batch.batch_no     IS '批次号 REC-yyyyMMdd-seq（库内 maxSeqOfDay 生成）';
COMMENT ON COLUMN integration_reconcile_batch.connector_id IS '连接器 id；0=全部连接器汇总（DEFAULT 0 避开 NULL 不参与 uk）';
COMMENT ON COLUMN integration_reconcile_batch.biz_date     IS '对账业务日（北京日；T+1 语义=昨日）';
COMMENT ON COLUMN integration_reconcile_batch.total_count  IS '当日出站消息总数';
COMMENT ON COLUMN integration_reconcile_batch.matched_count IS '对账一致数（remote_ack=true 置 MATCHED）';
COMMENT ON COLUMN integration_reconcile_batch.pending_count IS '待外呼数（PENDING）';
COMMENT ON COLUMN integration_reconcile_batch.long_count   IS '长款数（无三方账单源恒 0 如实）';
COMMENT ON COLUMN integration_reconcile_batch.short_count  IS '短款数（无三方账单源恒 0 如实）';
COMMENT ON COLUMN integration_reconcile_batch.failed_count IS '外呼失败数（FAILED）';
COMMENT ON COLUMN integration_reconcile_batch.total_amount IS '当日出站总金额（元）';
COMMENT ON COLUMN integration_reconcile_batch.diff_amount  IS '差额（无三方账单源恒 0 如实）';
COMMENT ON COLUMN integration_reconcile_batch.status       IS 'RUNNING=执行中 / DONE=完成 / FAILED=失败';
COMMENT ON COLUMN integration_reconcile_batch.started_at   IS '批次开始时刻';
COMMENT ON COLUMN integration_reconcile_batch.finished_at  IS '批次完成时刻';
