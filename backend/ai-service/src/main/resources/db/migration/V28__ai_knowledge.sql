-- =============================================================================
-- V28__ai_knowledge.sql
-- B47 卡7：A1 AI 知识库（项目知识/方案话术/法规合规）真实持久化、词法检索、引用溯源
--
-- 版本链：全库共享 flyway_schema_history，V15~V27 为 ai-service，本脚本占用 V28。
-- DDL 全部 IF NOT EXISTS，可在历史 ddl-auto=update 环境重入；DDL-only 不播种假知识。
--
-- 诚实口径：
--   1. 当前「向量化」为 PG 词法检索可用状态（ILIKE 标题/标签/正文加权召回），
--      index_status=INDEXED 即表示已进入可检索索引；语义向量（EMBEDDING 模型 + pgvector）
--      依赖多能力模型接入，属远期 Backlog，不在本卡伪造向量维度。
--   2. 「引用」来自本页真实检索事件（ai_knowledge_citation append-only），
--      话术/客服/内容生成的 RAG 自动检索联动为远期 Backlog，不编造跨模块引用流水。
--   3. 「引用准确率」不写死，由 citation 的 useful 反馈真实计算，无反馈显 —。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_knowledge_item (
    doc_id          BIGSERIAL    PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    category        VARCHAR(16)  NOT NULL,
    content         VARCHAR(8000) NOT NULL,
    tags            VARCHAR(500),
    source          VARCHAR(200),
    index_status    VARCHAR(16)  NOT NULL DEFAULT 'INDEXED',
    index_note      VARCHAR(500),
    refs_count      BIGINT       NOT NULL DEFAULT 0,
    staff_id        VARCHAR(64),
    staff_name      VARCHAR(64),
    store_code      VARCHAR(32),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_kb_category CHECK (category IN ('project', 'script', 'compliance')),
    CONSTRAINT chk_ai_kb_status   CHECK (index_status IN ('PENDING', 'INDEXED', 'FAILED')),
    CONSTRAINT chk_ai_kb_refs     CHECK (refs_count >= 0)
);
COMMENT ON TABLE ai_knowledge_item IS 'AI 知识库条目（项目知识/方案话术/法规合规；当前为词法检索索引，语义向量远期）';
COMMENT ON COLUMN ai_knowledge_item.category IS '分类：project 项目知识 / script 方案话术 / compliance 法规合规';
COMMENT ON COLUMN ai_knowledge_item.tags IS '检索标签，中文顿号/逗号分隔存储';
COMMENT ON COLUMN ai_knowledge_item.index_status IS '索引状态：PENDING 待索引 / INDEXED 已入词法索引可检索 / FAILED 索引失败（可重试）';
COMMENT ON COLUMN ai_knowledge_item.index_note IS '索引口径说明（词法检索/失败原因），语义向量接入后在此标注';
COMMENT ON COLUMN ai_knowledge_item.refs_count IS '累计引用：真实检索命中并打开溯源/引用的次数（ai_knowledge_citation 计数回写）';
CREATE INDEX IF NOT EXISTS idx_ai_kb_category ON ai_knowledge_item (category, doc_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_kb_status ON ai_knowledge_item (index_status);
CREATE INDEX IF NOT EXISTS idx_ai_kb_doc ON ai_knowledge_item (doc_id DESC);

CREATE TABLE IF NOT EXISTS ai_knowledge_citation (
    citation_id    BIGSERIAL    PRIMARY KEY,
    doc_id         BIGINT       NOT NULL REFERENCES ai_knowledge_item(doc_id) ON DELETE CASCADE,
    query          VARCHAR(200) NOT NULL,
    source_feature VARCHAR(32)  NOT NULL DEFAULT 'manual_search',
    useful         BOOLEAN,
    staff_id       VARCHAR(64),
    staff_name     VARCHAR(64),
    store_code     VARCHAR(32),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_kb_citation_feature CHECK (source_feature IN
        ('manual_search', 'scripts', 'chatbot', 'content'))
);
COMMENT ON TABLE ai_knowledge_citation IS '知识引用流水（append-only）：当前仅 manual_search 本页检索真实产生，RAG 联动后追加 scripts/chatbot/content';
COMMENT ON COLUMN ai_knowledge_citation.query IS '触发引用的检索词（热搜词按此列近 30 天聚合）';
COMMENT ON COLUMN ai_knowledge_citation.source_feature IS '引用来源：manual_search 本页检索（当前唯一）；scripts/chatbot/content 为远期 RAG 预留';
COMMENT ON COLUMN ai_knowledge_citation.useful IS '引用反馈：null 未反馈 / true 有用 / false 无用，用于真实引用准确率';
CREATE INDEX IF NOT EXISTS idx_ai_kb_citation_doc ON ai_knowledge_citation (doc_id, citation_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_kb_citation_created ON ai_knowledge_citation (created_at DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_knowledge_item' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_knowledge_item_updated_at') THEN
        CREATE TRIGGER trg_ai_knowledge_item_updated_at BEFORE UPDATE ON ai_knowledge_item
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END;
$$;
