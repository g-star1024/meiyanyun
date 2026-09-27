CREATE TABLE IF NOT EXISTS tag_factory_def (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL DEFAULT '',
    type VARCHAR(8) NOT NULL,
    sensitivity VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    value_type VARCHAR(16) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    sql TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    cover_count INT NOT NULL DEFAULT 0,
    refresh_cron VARCHAR(32) NOT NULL DEFAULT '',
    last_compute_at TIMESTAMPTZ,
    versions jsonb NOT NULL DEFAULT '[]'::jsonb,
    consumers jsonb NOT NULL DEFAULT '[]'::jsonb,
    tags jsonb NOT NULL DEFAULT '[]'::jsonb,
    owner VARCHAR(64) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_tag_factory_code UNIQUE (code),
    CONSTRAINT chk_tag_factory_type CHECK (type IN ('SQL','RULE','ML')),
    CONSTRAINT chk_tag_factory_sensitivity CHECK (sensitivity IN ('PUBLIC','INTERNAL','SENSITIVE')),
    CONSTRAINT chk_tag_factory_value_type CHECK (value_type IN ('ENUM','NUMBER','BOOLEAN','DATE')),
    CONSTRAINT chk_tag_factory_status CHECK (status IN ('DRAFT','PROCESSING','PUBLISHED','OFFLINE','PENDING_APPROVAL'))
);

COMMENT ON TABLE tag_factory_def IS 'T2-B2 标签工厂-标签定义表（DESIGN-T2 T2-03·集团级数据资产无 store_code 维度）';
COMMENT ON COLUMN tag_factory_def.id IS '主键';
COMMENT ON COLUMN tag_factory_def.code IS '标签编码（英文标识，全局唯一）';
COMMENT ON COLUMN tag_factory_def.name IS '标签名（业务唯一，服务层查重 409）';
COMMENT ON COLUMN tag_factory_def.category IS '业务分类（客户价值/风险预警/消费偏好/活跃度/AI 预测/消费行为等，自由词表）';
COMMENT ON COLUMN tag_factory_def.type IS '加工方式：SQL/RULE/ML';
COMMENT ON COLUMN tag_factory_def.sensitivity IS '敏感等级：PUBLIC/INTERNAL/SENSITIVE（SENSITIVE 发布需审批）';
COMMENT ON COLUMN tag_factory_def.value_type IS '值类型：ENUM/NUMBER/BOOLEAN/DATE';
COMMENT ON COLUMN tag_factory_def.description IS '标签描述';
COMMENT ON COLUMN tag_factory_def.sql IS '加工逻辑（SQL 片段/规则表达式/模型语句）';
COMMENT ON COLUMN tag_factory_def.status IS '状态：DRAFT/PROCESSING/PUBLISHED/OFFLINE/PENDING_APPROVAL';
COMMENT ON COLUMN tag_factory_def.cover_count IS '最近一次发布覆盖人数';
COMMENT ON COLUMN tag_factory_def.refresh_cron IS '刷新频率（人读文案，如 每日 02:00）';
COMMENT ON COLUMN tag_factory_def.last_compute_at IS '最近计算时间（NULL=未计算）';
COMMENT ON COLUMN tag_factory_def.versions IS '发布版本史 jsonb 数组（version/sql/publishedAt/publishedBy/coverCount）';
COMMENT ON COLUMN tag_factory_def.consumers IS '消费方 jsonb 数组（module/scene/usedAt）';
COMMENT ON COLUMN tag_factory_def.tags IS '附加标签 jsonb 字符串数组（实施期补登：前端 FactoryTag.tags 存续必需）';
COMMENT ON COLUMN tag_factory_def.owner IS '负责人（展示用姓名）';
COMMENT ON COLUMN tag_factory_def.created_at IS '创建时间';
COMMENT ON COLUMN tag_factory_def.updated_at IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_tag_factory_status ON tag_factory_def(status);
CREATE INDEX IF NOT EXISTS idx_tag_factory_type ON tag_factory_def(type);
