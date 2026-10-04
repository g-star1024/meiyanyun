-- ================================================================
-- V87 · T2 数据源注册表（棒⑥卡7 T2-01 数据源注册真源化）
-- 铁律0：仅新增 data_source；既有表零改动；零物理外键（照 V63-V86 先例）
-- 语义：CDC/Kafka/三方数据源「注册登记」真源化；接入运行时归 v2 移交（DESIGN-T2 §6 防蔓延）。
--       状态机仅 REGISTERED→DISABLED 直改（无审批链）；sync 端点对 THIRD_PARTY 做真实连通探测，
--       探测通过置 CONNECTED，CDC/KAFKA 类型如实拒绝（运行时未接入）。
-- ================================================================

CREATE TABLE IF NOT EXISTS data_source (
    id           BIGSERIAL   PRIMARY KEY,
    code         VARCHAR(64)  NOT NULL,
    name         VARCHAR(100) NOT NULL,
    type         VARCHAR(20)  NOT NULL,
    endpoint     VARCHAR(200),
    description  TEXT,
    status       VARCHAR(20)  NOT NULL DEFAULT 'REGISTERED',
    owner        VARCHAR(50)  NOT NULL,
    last_sync_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_data_source_code UNIQUE (code),
    CONSTRAINT chk_data_source_type   CHECK (type IN ('CDC','KAFKA','THIRD_PARTY')),
    CONSTRAINT chk_data_source_status CHECK (status IN ('REGISTERED','CONNECTED','DISABLED'))
);

COMMENT ON TABLE  data_source             IS 'T2 数据源注册表（CDC/Kafka/三方·接入运行时归 v2）';
COMMENT ON COLUMN data_source.code        IS '数据源编码（唯一）';
COMMENT ON COLUMN data_source.name        IS '数据源名称';
COMMENT ON COLUMN data_source.type        IS 'CDC=数据库变更捕获 / KAFKA=消息队列 / THIRD_PARTY=三方接口';
COMMENT ON COLUMN data_source.endpoint    IS '接入地址（THIRD_PARTY 为 http(s) URL，连通探测靶标）';
COMMENT ON COLUMN data_source.description IS '数据源描述';
COMMENT ON COLUMN data_source.status      IS 'REGISTERED=已注册 / CONNECTED=连通探测通过 / DISABLED=已停用';
COMMENT ON COLUMN data_source.owner       IS '负责人';
COMMENT ON COLUMN data_source.last_sync_at IS '最近同步/探测时间';
COMMENT ON COLUMN data_source.created_at  IS '创建时间';
COMMENT ON COLUMN data_source.updated_at  IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_data_source_status ON data_source (status);
CREATE INDEX IF NOT EXISTS idx_data_source_type   ON data_source (type);
