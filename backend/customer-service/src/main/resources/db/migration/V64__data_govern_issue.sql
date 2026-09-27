CREATE TABLE IF NOT EXISTS data_govern_issue (
    id BIGSERIAL PRIMARY KEY,
    rule_id BIGINT NOT NULL,
    rule_name VARCHAR(128) NOT NULL,
    table_name VARCHAR(64) NOT NULL,
    column_name VARCHAR(64) NOT NULL DEFAULT '',
    sample TEXT NOT NULL DEFAULT '',
    error_count INT NOT NULL DEFAULT 0,
    status VARCHAR(8) NOT NULL DEFAULT 'OPEN',
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT chk_govern_issue_status CHECK (status IN ('OPEN','RESOLVED','IGNORED'))
);

COMMENT ON TABLE data_govern_issue IS 'T2-B1 数据治理-质量问题单（rule_id 逻辑引用 data_govern_rule，零物理外键）';
COMMENT ON COLUMN data_govern_issue.id IS '主键';
COMMENT ON COLUMN data_govern_issue.rule_id IS '来源规则 id（逻辑引用 data_govern_rule.id）';
COMMENT ON COLUMN data_govern_issue.rule_name IS '来源规则名（冗余便于展示）';
COMMENT ON COLUMN data_govern_issue.table_name IS '目标表名（冗余）';
COMMENT ON COLUMN data_govern_issue.column_name IS '目标字段名（冗余）';
COMMENT ON COLUMN data_govern_issue.sample IS '异常样本示例';
COMMENT ON COLUMN data_govern_issue.error_count IS '异常条数（注：前端契约字段名为 count，count 为 SQL 保留字故列名避让，API 序列化映射回 count）';
COMMENT ON COLUMN data_govern_issue.status IS '状态：OPEN/RESOLVED/IGNORED';
COMMENT ON COLUMN data_govern_issue.detected_at IS '检出时间';
COMMENT ON COLUMN data_govern_issue.resolved_at IS '解决时间（IGNORED 时亦可回填）';

CREATE INDEX IF NOT EXISTS idx_govern_issue_rule ON data_govern_issue(rule_id);
CREATE INDEX IF NOT EXISTS idx_govern_issue_status ON data_govern_issue(status);
