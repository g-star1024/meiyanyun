-- =============================================================================
-- V26__ai_chat_session.sql
-- B47 AI 治理五期·卡5：A1-07 AI 客服工作台真实会话与 AI 回复落库
--
-- 版本链：全库共享 flyway_schema_history，V15~V25 为 ai-service，本脚本占用 V26。
-- DDL 全部 IF NOT EXISTS，可在历史 ddl-auto=update 环境重入；本脚本不做任何 seed，
-- 会话全部来自工作台真实操作（新建会话/顾客消息/AI 回复/转人工登记）。
--
-- 两张表：
--   ai_chat_session  客服会话头（一个顾客一个会话）：渠道 ai/human、末条消息冗余与未读计数
--                    由消息写入时同步维护；转人工仅为站内状态登记（M4-09 咨询工作台
--                    真实联动为远期 Backlog）。
--   ai_chat_message  会话消息行：customer=顾客消息 / ai=AI 回复（走 chatbot 功能 invoke
--                    全治理链：角色灰度/门店灰度/敏感词/配额/计费/ai_invoke_log）/
--                    staff=人工座席回复；AI 回复原文留档，invoke_log_id 关联 append-only 日志。
--
-- 诚实口径：不造历史会话、不造知识库命中（知识检索为远期能力，前端知识命中卡如实置空）；
--           KPI 全部来自本表当日真实计数，无昨日基数时环比不可算。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_chat_session (
    session_id              BIGSERIAL     PRIMARY KEY,
    session_no              VARCHAR(32)   NOT NULL,
    customer_name           VARCHAR(64)   NOT NULL DEFAULT '',
    channel                 VARCHAR(8)    NOT NULL DEFAULT 'ai',
    last_message            VARCHAR(500)  NOT NULL DEFAULT '',
    last_sender             VARCHAR(8)    NOT NULL DEFAULT 'customer',
    unread_count            INTEGER       NOT NULL DEFAULT 0,
    transferred             BOOLEAN       NOT NULL DEFAULT FALSE,
    transferred_at          TIMESTAMPTZ,
    transferred_by          VARCHAR(64),
    staff_id                VARCHAR(64),
    staff_name              VARCHAR(64),
    store_code              VARCHAR(32)   NOT NULL DEFAULT '',
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_chat_session_no UNIQUE (session_no),
    CONSTRAINT chk_ai_chat_session_channel CHECK (channel IN ('ai', 'human')),
    CONSTRAINT chk_ai_chat_session_sender CHECK (last_sender IN ('customer', 'ai', 'staff'))
);
COMMENT ON TABLE ai_chat_session IS 'AI 客服会话头（工作台真实操作沉淀；转人工仅站内登记，M4-09 真实联动为远期 Backlog）';
COMMENT ON COLUMN ai_chat_session.session_no IS '会话业务编号（CS+yyyyMMdd+4 位序列，按当日会话数生成）';
COMMENT ON COLUMN ai_chat_session.customer_name IS '顾客称呼（工作台录入，仅会话展示用，非会员主数据）';
COMMENT ON COLUMN ai_chat_session.channel IS '当前接待渠道：ai=AI 接待 / human=人工接待（转人工后翻转）';
COMMENT ON COLUMN ai_chat_session.last_message IS '末条消息内容冗余（截断 500 字，供会话列表预览）';
COMMENT ON COLUMN ai_chat_session.last_sender IS '末条消息发送方：customer 顾客 / ai AI 回复 / staff 人工座席';
COMMENT ON COLUMN ai_chat_session.unread_count IS '顾客新消息未读计数（座席打开会话时清零）';
COMMENT ON COLUMN ai_chat_session.transferred IS '是否已转人工（站内状态登记；M4-09 工作台真实联动为远期 Backlog）';
CREATE INDEX IF NOT EXISTS idx_ai_chat_session_created ON ai_chat_session (session_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_chat_session_channel ON ai_chat_session (channel, session_id DESC);

CREATE TABLE IF NOT EXISTS ai_chat_message (
    message_id              BIGSERIAL     PRIMARY KEY,
    session_id              BIGINT        NOT NULL,
    sender                  VARCHAR(8)    NOT NULL DEFAULT 'customer',
    content                 VARCHAR(2000) NOT NULL DEFAULT '',
    invoke_log_id           BIGINT,
    model_code              VARCHAR(128),
    total_tokens            INTEGER,
    cost_fen                BIGINT        NOT NULL DEFAULT 0,
    latency_ms              BIGINT,
    staff_id                VARCHAR(64),
    staff_name              VARCHAR(64),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_ai_chat_msg_session FOREIGN KEY (session_id)
        REFERENCES ai_chat_session (session_id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_chat_msg_sender CHECK (sender IN ('customer', 'ai', 'staff'))
);
COMMENT ON TABLE ai_chat_message IS 'AI 客服会话消息（customer 顾客 / ai 经 chatbot 功能真实出站 / staff 人工座席；append-only 不修改不删除）';
COMMENT ON COLUMN ai_chat_message.session_id IS '所属 ai_chat_session.session_id';
COMMENT ON COLUMN ai_chat_message.sender IS '发送方：customer 顾客 / ai AI 回复 / staff 人工座席';
COMMENT ON COLUMN ai_chat_message.content IS '消息内容（顾客/人工为录入原文，AI 为模型回复，截断 2000 字）';
COMMENT ON COLUMN ai_chat_message.invoke_log_id IS 'AI 回复关联 ai_invoke_log.log_id（token/费用/耗时真相在日志侧；顾客/人工消息为空）';
COMMENT ON COLUMN ai_chat_message.cost_fen IS 'AI 回复费用（分，取自 invoke 计费；非 AI 消息为 0）';
CREATE INDEX IF NOT EXISTS idx_ai_chat_msg_session ON ai_chat_message (session_id, message_id);
CREATE INDEX IF NOT EXISTS idx_ai_chat_msg_created ON ai_chat_message (message_id DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_chat_session' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_chat_session_updated_at') THEN
        CREATE TRIGGER trg_ai_chat_session_updated_at BEFORE UPDATE ON ai_chat_session
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END;
$$;
