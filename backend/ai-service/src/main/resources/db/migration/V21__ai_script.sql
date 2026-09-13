-- =============================================================================
-- V21__ai_script.sql
-- B46 AI 治理四期·卡4：智能话术库（破冰/升单/异议处理）真实持久化与采纳反馈
--
-- 版本链：全库共享 flyway_schema_history，V15~V20 为 ai-service，本脚本占用 V21。
-- DDL 全部 IF NOT EXISTS，可在历史 ddl-auto=update 环境重入；DDL-only 不播种假话术。
--
-- 表：ai_script 智能话术
--   手工新增/编辑（source=MANUAL）保存前经 A1-04 敏感词过滤；
--   抽屉内「AI 生成」走 scripts 功能 invoke 全治理链出站（source=AI），
--   用户确认保存后落本行，并以 invoke_log_id 关联 append-only 的 ai_invoke_log。
--   「插入咨询工作台」当前为采纳计数登记，真实推送到 M4 咨询工作台属跨域链路，登记后续 Backlog。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_script (
    script_id       BIGSERIAL    PRIMARY KEY,
    scene           VARCHAR(16)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    content         VARCHAR(4000) NOT NULL,
    source          VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    invoke_log_id   BIGINT,
    model_code      VARCHAR(128),
    rating          INTEGER      NOT NULL DEFAULT 5,
    adopted_count   BIGINT       NOT NULL DEFAULT 0,
    feedback_count  BIGINT       NOT NULL DEFAULT 0,
    staff_id        VARCHAR(64),
    staff_name      VARCHAR(64),
    store_code      VARCHAR(32),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_script_scene  CHECK (scene IN ('icebreak', 'upsell', 'objection')),
    CONSTRAINT chk_ai_script_source CHECK (source IN ('MANUAL', 'AI')),
    CONSTRAINT chk_ai_script_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_ai_script_adopted  CHECK (adopted_count >= 0),
    CONSTRAINT chk_ai_script_feedback CHECK (feedback_count >= 0)
);
COMMENT ON TABLE ai_script IS 'AI 智能话术库（破冰/升单/异议处理；手工与 AI 生成统一沉淀，保存前敏感词过滤）';
COMMENT ON COLUMN ai_script.scene IS '场景：icebreak 破冰 / upsell 升单 / objection 异议处理';
COMMENT ON COLUMN ai_script.source IS '来源：MANUAL 手工录入 / AI 抽屉内 AI 生成后保存';
COMMENT ON COLUMN ai_script.invoke_log_id IS 'AI 生成时关联 ai_invoke_log.log_id（手工话术为空）';
COMMENT ON COLUMN ai_script.rating IS '话术评分（1~5 星，当前默认 5，评价入口接入前为初始评分）';
COMMENT ON COLUMN ai_script.adopted_count IS '采纳数：点击「插入咨询工作台」累计计数（真实工作台推送为后续 Backlog）';
COMMENT ON COLUMN ai_script.feedback_count IS '反馈次数：点击「反馈」累计计数';
CREATE INDEX IF NOT EXISTS idx_ai_script_scene ON ai_script (scene, script_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_script_created ON ai_script (script_id DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_script' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_script_updated_at') THEN
        CREATE TRIGGER trg_ai_script_updated_at BEFORE UPDATE ON ai_script
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END;
$$;
