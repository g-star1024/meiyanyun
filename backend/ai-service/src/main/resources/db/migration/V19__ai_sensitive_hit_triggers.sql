-- =============================================================================
-- V19__ai_sensitive_hit_triggers.sql
-- B46 AI 治理四期·卡1：敏感词命中留痕 + 系统表 updated_at 自动刷新触发器
--
-- 版本链：全库共享 flyway_schema_history，V15~V18 为 ai-service，本脚本占用 V19。
-- DDL 全部 IF NOT EXISTS / DO 块守卫，可在历史 ddl-auto=update 环境重入。
--
-- 表清单：
--   ai_sensitive_hit 敏感词命中记录（出站前置拦截留痕，append-only；与 ai_invoke_log 解耦：
--                     拦截发生在 LLM 调用之前，不占配额、不产生 token/费用）
--
-- 触发器：
--   ai_set_updated_at() 通用 BEFORE UPDATE 函数，为 8 张含 updated_at 但实体侧时间戳只读的
--   ai 系统表自动刷新 updated_at = now()，补齐 V15/V17 仅 DEFAULT now() 不随 UPDATE 刷新的缺陷。
--   ai_quota / ai_feature_binding 实体侧已显式写 updated_at，不挂触发器（避免重复维护）。
-- =============================================================================

-- 1. 敏感词命中记录 -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_sensitive_hit (
    hit_id           BIGSERIAL    PRIMARY KEY,
    hit_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    word_id          BIGINT,
    word             VARCHAR(128) NOT NULL,
    category         VARCHAR(16)  NOT NULL,
    feature_code     VARCHAR(64),
    staff_id         VARCHAR(64),
    staff_name       VARCHAR(64),
    store_code       VARCHAR(32),
    context_snippet  VARCHAR(500),
    false_positive   BOOLEAN      NOT NULL DEFAULT FALSE,
    marked_by        VARCHAR(64),
    marked_at        TIMESTAMPTZ,
    CONSTRAINT chk_ai_sensitive_hit_category CHECK (category IN ('BANNED', 'INJECTION'))
);
COMMENT ON TABLE  ai_sensitive_hit IS 'AI 敏感词命中记录（出站前置拦截留痕，append-only；命中即 400 拒绝，不进 ai_invoke_log）';
COMMENT ON COLUMN ai_sensitive_hit.word_id IS '命中词库 ai_sensitive_word.word_id（词被删后保留快照 word/category，故可空）';
COMMENT ON COLUMN ai_sensitive_hit.context_snippet IS '用户输入原文截断 500 字（合规审计用，不出站）';
COMMENT ON COLUMN ai_sensitive_hit.false_positive IS '误报标注：人工标记后回流训练/词库治理';
CREATE INDEX IF NOT EXISTS idx_ai_sensitive_hit_at      ON ai_sensitive_hit (hit_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_sensitive_hit_word_id ON ai_sensitive_hit (word_id);
CREATE INDEX IF NOT EXISTS idx_ai_sensitive_hit_fp      ON ai_sensitive_hit (false_positive);

-- ai_sensitive_word 补 updated_at（V17 建表时只有 created_at，管理端编辑/启停需要排序与回显）
ALTER TABLE ai_sensitive_word ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- 2. updated_at 通用触发器 -----------------------------------------------------
CREATE OR REPLACE FUNCTION ai_set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    t TEXT;
    ai_tables TEXT[] := ARRAY[
        'ai_provider',
        'ai_model',
        'ai_alert_rule',
        'ai_feature_role',
        'ai_global_cfg',
        'ai_eval_task',
        'ai_experiment',
        'ai_sensitive_word'
    ];
BEGIN
    FOREACH t IN ARRAY ai_tables LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = t AND column_name = 'updated_at')
           AND NOT EXISTS (SELECT 1 FROM pg_trigger
                           WHERE tgname = 'trg_' || t || '_updated_at') THEN
            EXECUTE format(
                'CREATE TRIGGER trg_%I_updated_at BEFORE UPDATE ON %I
                 FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at()', t, t);
        END IF;
    END LOOP;
END;
$$;
