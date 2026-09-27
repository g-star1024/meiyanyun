-- ================================================================
-- V68 · T2 数据服务目录表（T2-B3 数据服务 · 活规格 frontend/src/stores/t2DataService.ts）
-- 铁律0：仅新增 data_service；既有表零改动
-- ================================================================

CREATE TABLE IF NOT EXISTS data_service (
    id              BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    endpoint        VARCHAR(200),
    method          VARCHAR(10),
    description     TEXT,
    owner           VARCHAR(50)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    call_count_24h  INTEGER      NOT NULL DEFAULT 0,
    avg_latency     INTEGER      NOT NULL DEFAULT 0,
    error_rate      NUMERIC(5,2) NOT NULL DEFAULT 0,
    fields          JSONB,
    tags            JSONB,
    version         VARCHAR(20)  NOT NULL DEFAULT 'v0.1',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_data_service_type    CHECK (type IN ('API','DATASET')),
    CONSTRAINT chk_data_service_method  CHECK (method IS NULL OR method IN ('GET','POST')),
    CONSTRAINT chk_data_service_status  CHECK (status IN ('PUBLISHED','DRAFT','DEPRECATED'))
);

COMMENT ON TABLE  data_service                 IS 'T2 数据服务目录（API/数据集）';
COMMENT ON COLUMN data_service.name           IS '服务名称';
COMMENT ON COLUMN data_service.type           IS '服务类型 API=接口服务 / DATASET=数据集';
COMMENT ON COLUMN data_service.endpoint       IS '接口路径（API 类型）';
COMMENT ON COLUMN data_service.method         IS '请求方式 GET/POST（API 类型）';
COMMENT ON COLUMN data_service.description    IS '服务描述';
COMMENT ON COLUMN data_service.owner          IS '负责人';
COMMENT ON COLUMN data_service.status         IS 'PUBLISHED=已发布 / DRAFT=草稿 / DEPRECATED=已下线';
COMMENT ON COLUMN data_service.call_count_24h IS '今日调用次数';
COMMENT ON COLUMN data_service.avg_latency    IS '平均耗时（毫秒）';
COMMENT ON COLUMN data_service.error_rate     IS '错误率（%）';
COMMENT ON COLUMN data_service.fields         IS '字段列表（jsonb 字符串数组）';
COMMENT ON COLUMN data_service.tags           IS '标签（jsonb 字符串数组）';
COMMENT ON COLUMN data_service.version        IS '版本号（发布时 v0.x→v1.0）';
COMMENT ON COLUMN data_service.created_at     IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_data_service_status ON data_service (status);
CREATE INDEX IF NOT EXISTS idx_data_service_type   ON data_service (type);
