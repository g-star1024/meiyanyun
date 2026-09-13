-- =============================================================================
-- V20__ai_content_record.sql
-- B46 AI 治理四期·卡3：内容生成（公众号/海报/短信）真实出站与下发留痕
--
-- 版本链：全库共享 flyway_schema_history，V15~V19 为 ai-service，本脚本占用 V20。
-- DDL 全部 IF NOT EXISTS，可在历史 ddl-auto=update 环境重入。
--
-- 表：ai_content_record 内容生成记录
--   一次真实生成（走 content 功能 invoke 出站）沉淀一行；下发 M5 为站内登记动作，
--   真实推送到营销中心（M5-01 活动/M5-13 素材）属跨域链路，登记为后续 Backlog。
--   invoke_log_id 关联 append-only 的 ai_invoke_log（token/费用/耗时真相在日志侧）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_content_record (
    record_id      BIGSERIAL    PRIMARY KEY,
    channel        VARCHAR(16)  NOT NULL,
    topic          VARCHAR(512) NOT NULL,
    title          VARCHAR(512) NOT NULL,
    content        VARCHAR(8000) NOT NULL,
    invoke_log_id  BIGINT,
    model_code     VARCHAR(128),
    total_tokens   INTEGER,
    cost_fen       BIGINT       NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL DEFAULT 'GENERATED',
    deployed_at    TIMESTAMPTZ,
    deployed_by    VARCHAR(64),
    staff_id       VARCHAR(64),
    staff_name     VARCHAR(64),
    store_code     VARCHAR(32),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_content_channel CHECK (channel IN ('wechat', 'poster', 'sms')),
    CONSTRAINT chk_ai_content_status  CHECK (status IN ('GENERATED', 'DEPLOYED'))
);
COMMENT ON TABLE ai_content_record IS 'AI 内容生成记录（公众号/海报/短信真实出站沉淀；append+状态流转，GENERATED→DEPLOYED）';
COMMENT ON COLUMN ai_content_record.channel IS '渠道：wechat 公众号文案 / poster 海报文案 / sms 短信文案';
COMMENT ON COLUMN ai_content_record.topic IS '用户输入的生成主题（真实出站原文）';
COMMENT ON COLUMN ai_content_record.title IS '标题：取主题截断 200 字';
COMMENT ON COLUMN ai_content_record.content IS '模型生成的文案全文（截断 8000 字）';
COMMENT ON COLUMN ai_content_record.invoke_log_id IS '关联 ai_invoke_log.log_id（token/费用/耗时真相在日志侧）';
COMMENT ON COLUMN ai_content_record.status IS '状态：GENERATED 已生成 / DEPLOYED 已登记下发 M5';
CREATE INDEX IF NOT EXISTS idx_ai_content_channel_created ON ai_content_record (channel, record_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_content_created         ON ai_content_record (record_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_content_status          ON ai_content_record (status);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_content_record' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_content_record_updated_at') THEN
        CREATE TRIGGER trg_ai_content_record_updated_at BEFORE UPDATE ON ai_content_record
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END;
$$;
