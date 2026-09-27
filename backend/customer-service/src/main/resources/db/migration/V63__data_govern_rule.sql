CREATE TABLE IF NOT EXISTS data_govern_rule (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    table_name VARCHAR(64) NOT NULL,
    column_name VARCHAR(64) NOT NULL DEFAULT '',
    rule_type VARCHAR(16) NOT NULL,
    severity VARCHAR(8) NOT NULL,
    expression TEXT NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_check_at TIMESTAMPTZ,
    pass_rate NUMERIC(5,2) NOT NULL DEFAULT 100,
    error_count INT NOT NULL DEFAULT 0,
    owner VARCHAR(32) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_govern_rule_type CHECK (rule_type IN ('NOT_NULL','UNIQUE','RANGE','REGEX','CUSTOM')),
    CONSTRAINT chk_govern_rule_severity CHECK (severity IN ('HIGH','MEDIUM','LOW'))
);

COMMENT ON TABLE data_govern_rule IS 'T2-B1 数据治理-质量规则表（多门店集团级·治理对象为库表字段非单店行）';
COMMENT ON COLUMN data_govern_rule.id IS '主键';
COMMENT ON COLUMN data_govern_rule.name IS '规则名';
COMMENT ON COLUMN data_govern_rule.table_name IS '目标表名';
COMMENT ON COLUMN data_govern_rule.column_name IS '目标字段名（可为空串=表级规则）';
COMMENT ON COLUMN data_govern_rule.rule_type IS '规则类型：NOT_NULL/UNIQUE/RANGE/REGEX/CUSTOM';
COMMENT ON COLUMN data_govern_rule.severity IS '严重度：HIGH/MEDIUM/LOW';
COMMENT ON COLUMN data_govern_rule.expression IS '规则表达式（SQL 片段）';
COMMENT ON COLUMN data_govern_rule.enabled IS '是否启用';
COMMENT ON COLUMN data_govern_rule.last_check_at IS '最近检测时间（NULL=未检测，如 disabled 规则）';
COMMENT ON COLUMN data_govern_rule.pass_rate IS '最近检测通过率（%）';
COMMENT ON COLUMN data_govern_rule.error_count IS '最近检测异常数';
COMMENT ON COLUMN data_govern_rule.owner IS '负责人';
COMMENT ON COLUMN data_govern_rule.created_at IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_govern_rule_enabled ON data_govern_rule(enabled);
