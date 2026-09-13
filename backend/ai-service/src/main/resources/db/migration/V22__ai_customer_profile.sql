-- =============================================================================
-- V22__ai_customer_profile.sql
-- B47 AI 治理五期·卡1：A1-02 客户画像引擎真实出站与画像快照落库
--
-- 版本链：全库共享 flyway_schema_history，V15~V21 为 ai-service，本脚本占用 V22。
-- DDL 全部 IF NOT EXISTS，可在历史 ddl-auto=update 环境重入。
--
-- 表：ai_customer_profile 客户画像快照
--   一次真实画像生成（走 profile 功能 invoke 出站，输入为客户域经 X-Internal-Token
--   取回的画像上下文）沉淀一行；LLM 结构化输出（价值分/分群/标签）解析后落列，
--   原文存 raw_output 供复核。invoke_log_id 关联 append-only 的 ai_invoke_log。
--   applied_to_segment 为「应用到分群」站内登记（M3-06 标签工厂/M3-14 分群跨域推送为远期 Backlog）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_customer_profile (
    profile_id           BIGSERIAL     PRIMARY KEY,
    customer_id          VARCHAR(16)   NOT NULL,
    customer_name        VARCHAR(32)   NOT NULL,
    store_code           VARCHAR(32),
    value_score          INTEGER       NOT NULL DEFAULT 0,
    groups_json          VARCHAR(2000) NOT NULL DEFAULT '[]',
    tags_json            VARCHAR(4000) NOT NULL DEFAULT '[]',
    raw_output           VARCHAR(8000) NOT NULL DEFAULT '',
    invoke_log_id        BIGINT,
    model_code           VARCHAR(128),
    total_tokens         INTEGER,
    cost_fen             BIGINT        NOT NULL DEFAULT 0,
    applied_to_segment   BOOLEAN       NOT NULL DEFAULT FALSE,
    applied_at           TIMESTAMPTZ,
    applied_by           VARCHAR(64),
    staff_id             VARCHAR(64),
    staff_name           VARCHAR(64),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_profile_value_score CHECK (value_score BETWEEN 0 AND 100)
);
COMMENT ON TABLE ai_customer_profile IS 'AI 客户画像快照（profile 功能真实出站沉淀；一客户多次生成多行，最近一行为当前画像）';
COMMENT ON COLUMN ai_customer_profile.customer_id IS '客户编号（客户域 customer.customer_id，跨服务锚点）';
COMMENT ON COLUMN ai_customer_profile.customer_name IS '客户姓名快照（画像生成时刻）';
COMMENT ON COLUMN ai_customer_profile.value_score IS '客户价值分 0~100（LLM 结构化输出解析，解析失败兜底 0）';
COMMENT ON COLUMN ai_customer_profile.groups_json IS '所属分群名数组 JSON（模型输出，群体级标签，A1-17 脱敏口径）';
COMMENT ON COLUMN ai_customer_profile.tags_json IS '画像标签数组 JSON：[{label,status}]，status 为前端色板键';
COMMENT ON COLUMN ai_customer_profile.raw_output IS '模型画像输出原文（截断 8000 字，供人工复核）';
COMMENT ON COLUMN ai_customer_profile.invoke_log_id IS '关联 ai_invoke_log.log_id（token/费用/耗时真相在日志侧）';
COMMENT ON COLUMN ai_customer_profile.applied_to_segment IS '是否已登记应用到分群（站内登记；M3-06/M3-14 跨域推送为远期 Backlog）';
CREATE INDEX IF NOT EXISTS idx_ai_profile_customer_created ON ai_customer_profile (customer_id, profile_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_profile_created          ON ai_customer_profile (profile_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_profile_applied          ON ai_customer_profile (applied_to_segment)
    WHERE applied_to_segment = TRUE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_customer_profile' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_customer_profile_updated_at') THEN
        CREATE TRIGGER trg_ai_customer_profile_updated_at BEFORE UPDATE ON ai_customer_profile
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END;
$$;
