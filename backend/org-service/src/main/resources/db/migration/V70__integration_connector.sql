-- ================================================================
-- V70 · T3 数据中台 连接器目录表（T3-B1 · DESIGN-T3 §三 · 活规格 frontend/src/stores/t3Integration.ts）
-- 属主 org-service（全库共享 flyway_schema_history，V69 customer 侧 data_service_permission 之后空号 V70）
-- 铁律0：仅新增 integration_connector；既有表零改动；CREATE TABLE IF NOT EXISTS 双库可重入
-- 红线：单向镜像（UNIDIRECTIONAL）绝不反向写资金池；status 默认 DISCONNECTED 诚实态
-- ================================================================

CREATE TABLE IF NOT EXISTS integration_connector (
    id              BIGSERIAL    PRIMARY KEY,
    code            VARCHAR(32)  NOT NULL,
    type            VARCHAR(16)  NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    endpoint        VARCHAR(256) NOT NULL,
    credential_key  VARCHAR(128),
    status          VARCHAR(16)  NOT NULL DEFAULT 'DISCONNECTED',
    last_sync_at    TIMESTAMPTZ,
    last_error      VARCHAR(256),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_integration_connector_code   UNIQUE (code),
    CONSTRAINT chk_integration_connector_type  CHECK (type IN ('PAYMENT','INSURANCE','WECOM','TAX','ADS','KINGDEE','YONYOU')),
    CONSTRAINT chk_integration_connector_status CHECK (status IN ('CONNECTED','DISCONNECTED','ERROR'))
);

COMMENT ON TABLE  integration_connector                IS 'T3 数据中台 连接器目录（支付/医保/企微/税控/广告/金蝶/用友）';
COMMENT ON COLUMN integration_connector.code           IS '连接器编码（种子九码 CONN-WX/ALI/UMS/SHYB/WECOM/BW/OCEAN/KD/YY）';
COMMENT ON COLUMN integration_connector.type           IS 'PAYMENT=支付 / INSURANCE=医保 / WECOM=企业微信 / TAX=税控 / ADS=广告 / KINGDEE=金蝶 / YONYOU=用友';
COMMENT ON COLUMN integration_connector.name           IS '中文展示名';
COMMENT ON COLUMN integration_connector.endpoint       IS '三方 API 基地址（保存校验 https；http 需 force_insecure 二次确认）';
COMMENT ON COLUMN integration_connector.credential_key IS '凭证引用名（掩码回显 前4****后4；密钥本体在 external_integration 单点持钥，本表不复制）';
COMMENT ON COLUMN integration_connector.status         IS 'CONNECTED=已连通（仅真实探测驱动）/ DISCONNECTED=未连接 / ERROR=探测失败（SYNCING 为运行瞬时态不落库）';
COMMENT ON COLUMN integration_connector.last_sync_at   IS '最近同步时间（B2 单向镜像引擎刷新）';
COMMENT ON COLUMN integration_connector.last_error     IS '最近探测/同步错误如实文案';
COMMENT ON COLUMN integration_connector.created_at     IS '创建时间';
COMMENT ON COLUMN integration_connector.updated_at     IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_integration_connector_type   ON integration_connector (type);
CREATE INDEX IF NOT EXISTS idx_integration_connector_status ON integration_connector (status);
