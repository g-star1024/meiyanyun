-- =============================================================================
-- V24__ai_churn_prediction.sql
-- B47 AI 治理五期·卡3：A1-09 流失预警真实出站与流失候选快照落库
--
-- 版本链：全库共享 flyway_schema_history，V15~V23 为 ai-service，本脚本占用 V24。
-- DDL 全部 IF NOT EXISTS，可在历史 ddl-auto=update 环境重入。
--
-- 表：ai_churn_prediction 流失预警候选快照
--   一次「运行评分」聚成一批（batch_no），候选为 txn-service 已收款订单经 X-Internal-Token
--   投影的真实成交客户（按距上次成交天数倒序——久未到店优先，与复购候选「最近成交优先」相反），
--   逐客户走 churn 功能 invoke 出站（到店间隔/消费下降率/RFM/卡余额等真实信号入 prompt），
--   LLM 结构化输出（流失风险分/关键因子/建议干预）容错解析后一行一客户沉淀；关键因子须取自
--   真实信号（非模型编造客户事实），原文存 raw_output 供复核。invoke_log_id 关联 append-only
--   的 ai_invoke_log。intervene 为站内登记（真实流失管理 M3-10、唤醒活动 M2-17、推送 M5-03
--   下发均为远期 Backlog）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_churn_prediction (
    prediction_id           BIGSERIAL     PRIMARY KEY,
    batch_no                VARCHAR(24)   NOT NULL,
    customer_id             VARCHAR(16)   NOT NULL,
    customer_name           VARCHAR(32)   NOT NULL,
    store_code              VARCHAR(32),
    risk_level              VARCHAR(8)    NOT NULL DEFAULT 'low',
    score                   INTEGER       NOT NULL DEFAULT 0,
    key_factor              VARCHAR(64)   NOT NULL DEFAULT '',
    suggested_action        VARCHAR(64)   NOT NULL DEFAULT '',
    last_visit_date         VARCHAR(16)   NOT NULL DEFAULT '',
    recency_days            BIGINT,
    spend_decline_pct       INTEGER,
    signals_json            VARCHAR(4000) NOT NULL DEFAULT '{}',
    raw_output              VARCHAR(8000) NOT NULL DEFAULT '',
    invoke_log_id           BIGINT,
    model_code              VARCHAR(128),
    total_tokens            INTEGER,
    cost_fen                BIGINT        NOT NULL DEFAULT 0,
    intervene_registered    BOOLEAN       NOT NULL DEFAULT FALSE,
    intervene_at            TIMESTAMPTZ,
    intervene_by            VARCHAR(64),
    staff_id                VARCHAR(64),
    staff_name              VARCHAR(64),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_churn_level CHECK (risk_level IN ('high', 'mid', 'low')),
    CONSTRAINT chk_ai_churn_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT chk_ai_churn_decline CHECK (spend_decline_pct IS NULL OR spend_decline_pct BETWEEN 0 AND 100)
);
COMMENT ON TABLE ai_churn_prediction IS 'AI 流失预警候选快照（churn 功能真实出站沉淀；一次运行一批，一客户一行，最近一批为当前风险榜）';
COMMENT ON COLUMN ai_churn_prediction.batch_no IS '评分批次号（CH+日期+序号），同一次运行的候选共享一个批次';
COMMENT ON COLUMN ai_churn_prediction.customer_id IS '客户编号（txn/customer 域 customer_id，跨服务锚点）';
COMMENT ON COLUMN ai_churn_prediction.risk_level IS '风险分级：high 高风险（score>=85）/ mid 中风险（60~84）/ low 低风险（<60），由真实分数阈值映射';
COMMENT ON COLUMN ai_churn_prediction.score IS '流失风险分 0~100（LLM 结构化输出解析，分数越高流失风险越大，解析失败兜底 0）';
COMMENT ON COLUMN ai_churn_prediction.key_factor IS '关键因子文案（模型须从入模真实信号中择一概括，非编造客户事实）';
COMMENT ON COLUMN ai_churn_prediction.suggested_action IS '建议干预文案（模型给运营建议，非已执行动作，真实下发为远期）';
COMMENT ON COLUMN ai_churn_prediction.last_visit_date IS '最近一次真实已收款成交日期（yyyy-MM-dd，来自交易域投影）';
COMMENT ON COLUMN ai_churn_prediction.recency_days IS '距上次成交天数（真实交易计算，久未到店为流失首因）';
COMMENT ON COLUMN ai_churn_prediction.spend_decline_pct IS '近 90 天对比再前 90 天消费金额下降百分比 0~100（前期为 0 时为 null）';
COMMENT ON COLUMN ai_churn_prediction.signals_json IS '入模真实信号 JSON（RFM/到店间隔/消费下降率/卡余额等，全部来自 txn/customer 域投影）';
COMMENT ON COLUMN ai_churn_prediction.raw_output IS '模型评分输出原文（截断 8000 字，供人工复核）';
COMMENT ON COLUMN ai_churn_prediction.invoke_log_id IS '关联 ai_invoke_log.log_id（token/费用/耗时真相在日志侧）';
COMMENT ON COLUMN ai_churn_prediction.intervene_registered IS '是否已登记干预（站内登记；M3-10 流失管理/M2-17 唤醒活动/M5-03 推送真实下发均为远期 Backlog）';
CREATE INDEX IF NOT EXISTS idx_ai_churn_batch_score  ON ai_churn_prediction (batch_no, score DESC);
CREATE INDEX IF NOT EXISTS idx_ai_churn_customer      ON ai_churn_prediction (customer_id, prediction_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_churn_created       ON ai_churn_prediction (prediction_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_churn_intervene     ON ai_churn_prediction (intervene_registered)
    WHERE intervene_registered = TRUE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_churn_prediction' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_churn_prediction_updated_at') THEN
        CREATE TRIGGER trg_ai_churn_prediction_updated_at BEFORE UPDATE ON ai_churn_prediction
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END;
$$;
