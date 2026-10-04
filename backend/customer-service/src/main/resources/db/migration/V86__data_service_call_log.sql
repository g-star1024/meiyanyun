-- ================================================================
-- V86 · T2 数据服务调用日志（棒⑥卡7 T2-04 调用统计真采集）
-- 铁律0：仅新增 data_service_call_log；既有表零改动；零物理外键（照 V63-V85 先例）
-- 语义：调用方逐条上报（POST /services/{id}/calls）；
--       data_service.call_count_24h/avg_latency/error_rate 三列废弃直读，
--       列表视图改读时聚合本表 24h 窗口（COUNT/AVG/错误率）。
-- ================================================================

CREATE TABLE IF NOT EXISTS data_service_call_log (
    id         BIGSERIAL   PRIMARY KEY,
    service_id BIGINT      NOT NULL,
    called_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    latency_ms INTEGER     NOT NULL DEFAULT 0,
    success    BOOLEAN     NOT NULL DEFAULT TRUE
);

COMMENT ON TABLE  data_service_call_log            IS 'T2 数据服务调用日志（逐条上报·读时聚合）';
COMMENT ON COLUMN data_service_call_log.service_id IS '数据服务 ID（逻辑引用 data_service.id）';
COMMENT ON COLUMN data_service_call_log.called_at  IS '调用时间';
COMMENT ON COLUMN data_service_call_log.latency_ms IS '耗时（毫秒）';
COMMENT ON COLUMN data_service_call_log.success    IS '是否成功（false 计入错误率）';

CREATE INDEX IF NOT EXISTS idx_ds_call_log_service_time ON data_service_call_log (service_id, called_at);
