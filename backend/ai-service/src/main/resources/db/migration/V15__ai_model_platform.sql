-- =============================================================================
-- V15__ai_model_platform.sql
-- AI 模型平台系统表（ai-service，端口 8089，网关前缀 /api/ai）
--
-- 版本号分配说明：
--   全库所有服务共享同一张 flyway_schema_history，版本号全局递增、不得复用：
--     V1/V2/V3/V5/V9 customer-service，V4/V6/V7/V8 finance-service，
--     V10 audit-service，V11~V14 marketing-service，本脚本占用 V15（ai-service 首个版本）。
--   DDL 全部 CREATE TABLE IF NOT EXISTS：
--     ① 全新环境随服务启动首次迁移建表；② 历史 JPA ddl-auto=update 环境可重入不报错。
--   仅做 CREATE，不做任何 ALTER（后续演进走 V16+）。
--
-- 表清单：
--   ai_provider        供应商（火山方舟/OpenAI 兼容…），API Key 经 AES-GCM 加密落库
--   ai_model           供应商下的模型登记与参数、连通状态、计费单价
--   ai_feature_binding AI 功能（画像/流失/话术/排班/内容/治理）→模型绑定与灰度
--   ai_feature_role    功能 × 角色开放开关（A1 管理台功能权限矩阵）
--   ai_invoke_log      统一调用日志（调用人/门店/功能/token/耗时/成败/费用分）
--   ai_approval        模型/供应商上线审批（A1 治理台审批列表）
--   ai_global_cfg      AI 管理台全局配置单行表（默认模型/灰度/留存/合规开关）
--   ai_quota（配额）二期再建，本期不落地。
-- =============================================================================

-- 1. 供应商 -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_provider (
    provider_id     BIGSERIAL    PRIMARY KEY,
    provider_code   VARCHAR(64)  NOT NULL,
    provider_name   VARCHAR(128) NOT NULL,
    base_url        VARCHAR(512) NOT NULL,
    api_key_cipher  TEXT,
    api_key_mask    VARCHAR(40),
    protocol        VARCHAR(32)  NOT NULL DEFAULT 'OPENAI',
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_provider_code UNIQUE (provider_code)
);
COMMENT ON TABLE  ai_provider IS 'AI 供应商（OpenAI 兼容协议）';
COMMENT ON COLUMN ai_provider.provider_code  IS '供应商编码（英文唯一，如 ark）';
COMMENT ON COLUMN ai_provider.base_url       IS 'OpenAI 兼容根地址（不以斜杠结尾，系统补 /chat/completions）';
COMMENT ON COLUMN ai_provider.api_key_cipher IS 'API Key 的 AES-GCM 密文（Base64，禁明文/禁日志）';
COMMENT ON COLUMN ai_provider.api_key_mask   IS 'API Key 掩码回显（如 ark-****ab12）';
COMMENT ON COLUMN ai_provider.protocol       IS '协议：OPENAI（OpenAI Chat Completions 兼容）';

-- 2. 模型 ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_model (
    model_id          BIGSERIAL     PRIMARY KEY,
    provider_id       BIGINT        NOT NULL REFERENCES ai_provider (provider_id),
    model_code        VARCHAR(128)  NOT NULL,
    display_name      VARCHAR(128)  NOT NULL,
    capabilities      VARCHAR(255)  NOT NULL DEFAULT 'CHAT',
    context_window    INTEGER,
    temperature       NUMERIC(4,2),
    top_p             NUMERIC(4,2),
    max_tokens        INTEGER,
    priority          INTEGER       NOT NULL DEFAULT 100,
    enabled           BOOLEAN       NOT NULL DEFAULT FALSE,
    conn_status       VARCHAR(16)   NOT NULL DEFAULT 'UNKNOWN',
    conn_message      VARCHAR(512),
    conn_checked_at   TIMESTAMPTZ,
    input_price       NUMERIC(12,4),
    output_price      NUMERIC(12,4),
    updated_by        VARCHAR(64),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_model_provider_code UNIQUE (provider_id, model_code)
);
COMMENT ON TABLE  ai_model IS 'AI 模型登记（供应商下具体模型与参数）';
COMMENT ON COLUMN ai_model.model_code     IS '外部模型 ID（如 ark-code-latest）';
COMMENT ON COLUMN ai_model.capabilities   IS '能力集合，逗号分隔：CHAT/EMBEDDING/VISION';
COMMENT ON COLUMN ai_model.conn_status    IS '最近连通性测试：SUCCESS/FAIL/UNKNOWN';
COMMENT ON COLUMN ai_model.input_price    IS '输入单价（元/百万 token，可空=不参与费用核算）';
COMMENT ON COLUMN ai_model.output_price   IS '输出单价（元/百万 token，可空=不参与费用核算）';
CREATE INDEX IF NOT EXISTS idx_ai_model_provider ON ai_model (provider_id);

-- 3. 功能绑定 -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_feature_binding (
    binding_id        BIGSERIAL    PRIMARY KEY,
    feature_code      VARCHAR(64)  NOT NULL,
    feature_name      VARCHAR(128) NOT NULL,
    model_id          BIGINT       REFERENCES ai_model (model_id),
    store_scope       VARCHAR(16)  NOT NULL DEFAULT 'ALL',
    store_codes       VARCHAR(2048),
    prompt_template   TEXT,
    param_overrides   VARCHAR(1024),
    enabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    require_approval  BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_by        VARCHAR(64),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_feature_code UNIQUE (feature_code)
);
COMMENT ON TABLE  ai_feature_binding IS 'AI 功能 → 模型绑定（含门店灰度与提示词模板）';
COMMENT ON COLUMN ai_feature_binding.store_scope     IS '门店范围：ALL 全部门店 / SPECIFIED 指定门店';
COMMENT ON COLUMN ai_feature_binding.store_codes     IS '指定门店编码，逗号分隔（store_scope=SPECIFIED 时生效）';
COMMENT ON COLUMN ai_feature_binding.param_overrides IS '参数覆盖 JSON（temperature/topP/maxTokens 等）';

-- 4. 功能 × 角色开关（A1 管理台功能权限矩阵，独立于 T1 RBAC） -------------------
CREATE TABLE IF NOT EXISTS ai_feature_role (
    feature_code  VARCHAR(64) NOT NULL,
    role_code     VARCHAR(32) NOT NULL,
    enabled       BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_feature_role PRIMARY KEY (feature_code, role_code)
);
COMMENT ON TABLE ai_feature_role IS 'AI 功能 × 角色开放开关（功能灰度矩阵，非 T1 RBAC 操作权限）';

-- 5. 调用日志 -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_invoke_log (
    log_id           BIGSERIAL    PRIMARY KEY,
    invoked_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    staff_id         VARCHAR(64),
    staff_name       VARCHAR(64),
    store_code       VARCHAR(32),
    feature_code     VARCHAR(64),
    provider_code    VARCHAR(64),
    model_code       VARCHAR(128),
    prompt_snippet   VARCHAR(500),
    output_snippet   VARCHAR(500),
    prompt_tokens    INTEGER,
    completion_tokens INTEGER,
    total_tokens     INTEGER,
    latency_ms       BIGINT,
    success          BOOLEAN      NOT NULL DEFAULT TRUE,
    error_code       VARCHAR(64),
    cost_fen         BIGINT       NOT NULL DEFAULT 0
);
COMMENT ON TABLE ai_invoke_log IS 'AI 统一调用日志（入出参截断、token、耗时、费用分）';
CREATE INDEX IF NOT EXISTS idx_ai_invoke_at      ON ai_invoke_log (invoked_at);
CREATE INDEX IF NOT EXISTS idx_ai_invoke_feature ON ai_invoke_log (feature_code);
CREATE INDEX IF NOT EXISTS idx_ai_invoke_store   ON ai_invoke_log (store_code);

-- 6. 上线审批 -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_approval (
    approval_id    BIGSERIAL    PRIMARY KEY,
    approval_type  VARCHAR(32)  NOT NULL,
    target_id      BIGINT,
    content        VARCHAR(512) NOT NULL,
    applicant      VARCHAR(64)  NOT NULL,
    applied_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_by     VARCHAR(64),
    decided_at     TIMESTAMPTZ,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    opinion        VARCHAR(512)
);
COMMENT ON TABLE  ai_approval IS 'AI 模型/供应商上线审批';
COMMENT ON COLUMN ai_approval.approval_type IS '类型：PROVIDER/MODEL/BINDING';
COMMENT ON COLUMN ai_approval.status        IS '状态：PENDING/APPROVED/REJECTED';
CREATE INDEX IF NOT EXISTS idx_ai_approval_status ON ai_approval (status);

-- 7. 全局配置（单行 cfg_id=1） -------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_global_cfg (
    cfg_id             INTEGER     PRIMARY KEY,
    default_model_id   BIGINT      REFERENCES ai_model (model_id),
    gray_scale         INTEGER     NOT NULL DEFAULT 100,
    retention_months   INTEGER     NOT NULL DEFAULT 12,
    sensitive_check    BOOLEAN     NOT NULL DEFAULT TRUE,
    explainability     BOOLEAN     NOT NULL DEFAULT TRUE,
    auto_audit         BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_by         VARCHAR(64),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_gray_scale CHECK (gray_scale BETWEEN 0 AND 100)
);
COMMENT ON TABLE ai_global_cfg IS 'AI 管理台全局配置（单行 cfg_id=1）';
INSERT INTO ai_global_cfg (cfg_id) VALUES (1) ON CONFLICT (cfg_id) DO NOTHING;
