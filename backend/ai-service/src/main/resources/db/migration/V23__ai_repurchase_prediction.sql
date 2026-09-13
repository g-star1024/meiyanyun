-- =============================================================================
-- V23__ai_repurchase_prediction.sql
-- B47 AI 治理五期·卡2：A1-03 复购预测真实出站与复购候选快照落库
--
-- 版本链：全库共享 flyway_schema_history，V15~V22 为 ai-service，本脚本占用 V23。
-- DDL 全部 IF NOT EXISTS，可在历史 ddl-auto=update 环境重入。
--
-- 表：ai_repurchase_prediction 复购预测候选快照
--   一次「运行预测」按选定预测周期（week/month/quarter）聚成一批（batch_no），
--   候选为 txn-service 已收款订单经 X-Internal-Token 投影的真实成交客户，逐客户走
--   repurchase 功能 invoke 出站（RFM/周期/卡余额等真实信号入 prompt），LLM 结构化输出
--   （复购概率/推荐时机/预计转化）容错解析后一行一客户沉淀；推荐项目取该客户最近一次
--   真实成交项目（非模型编造），原文存 raw_output 供复核。invoke_log_id 关联 append-only
--   的 ai_invoke_log。followup/push 为站内登记（真实跟进任务 M3-08、推送 M5-03 为远期 Backlog）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_repurchase_prediction (
    prediction_id         BIGSERIAL     PRIMARY KEY,
    batch_no              VARCHAR(24)   NOT NULL,
    customer_id           VARCHAR(16)   NOT NULL,
    customer_name         VARCHAR(32)   NOT NULL,
    store_code            VARCHAR(32),
    period                VARCHAR(16)   NOT NULL,
    horizon_days          INTEGER       NOT NULL DEFAULT 7,
    project_code          VARCHAR(16)   NOT NULL DEFAULT 'other',
    project_name          VARCHAR(64)   NOT NULL DEFAULT '',
    prob                  INTEGER       NOT NULL DEFAULT 0,
    timing                VARCHAR(32)   NOT NULL DEFAULT '',
    expected_amount       BIGINT        NOT NULL DEFAULT 0,
    signals_json          VARCHAR(4000) NOT NULL DEFAULT '{}',
    raw_output            VARCHAR(8000) NOT NULL DEFAULT '',
    invoke_log_id         BIGINT,
    model_code            VARCHAR(128),
    total_tokens          INTEGER,
    cost_fen              BIGINT        NOT NULL DEFAULT 0,
    followup_registered   BOOLEAN       NOT NULL DEFAULT FALSE,
    followup_at           TIMESTAMPTZ,
    followup_by           VARCHAR(64),
    push_registered       BOOLEAN       NOT NULL DEFAULT FALSE,
    push_at               TIMESTAMPTZ,
    push_by               VARCHAR(64),
    staff_id              VARCHAR(64),
    staff_name            VARCHAR(64),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_repurchase_period CHECK (period IN ('week', 'month', 'quarter')),
    CONSTRAINT chk_ai_repurchase_prob   CHECK (prob BETWEEN 0 AND 100)
);
COMMENT ON TABLE ai_repurchase_prediction IS 'AI 复购预测候选快照（repurchase 功能真实出站沉淀；一次运行一批，一客户一行，最近一批为当前榜单）';
COMMENT ON COLUMN ai_repurchase_prediction.batch_no IS '预测批次号（RP+日期+序号），同一次运行的候选共享一个批次';
COMMENT ON COLUMN ai_repurchase_prediction.customer_id IS '客户编号（txn/customer 域 customer_id，跨服务锚点）';
COMMENT ON COLUMN ai_repurchase_prediction.period IS '预测周期：week 本周 / month 本月 / quarter 本季';
COMMENT ON COLUMN ai_repurchase_prediction.horizon_days IS '预测窗口天数（7/30/90），由周期映射';
COMMENT ON COLUMN ai_repurchase_prediction.project_code IS '项目品类归类 code（skin/inject/anti/body/other，由最近真实成交项目名规则归类）';
COMMENT ON COLUMN ai_repurchase_prediction.project_name IS '推荐项目：该客户最近一次真实已收款订单项目中文名（非模型编造）';
COMMENT ON COLUMN ai_repurchase_prediction.prob IS '复购概率 0~100（LLM 结构化输出解析，解析失败兜底 0）';
COMMENT ON COLUMN ai_repurchase_prediction.timing IS '推荐时机文案（模型在 3天内/本周/2周内/本月/本季 中择一）';
COMMENT ON COLUMN ai_repurchase_prediction.expected_amount IS '预计转化金额（分，模型基于真实客单价/概率估算，失败兜底 0）';
COMMENT ON COLUMN ai_repurchase_prediction.signals_json IS '入模真实信号 JSON（RFM/平均成交间隔/卡余额等，全部来自 txn/customer 域投影）';
COMMENT ON COLUMN ai_repurchase_prediction.raw_output IS '模型预测输出原文（截断 8000 字，供人工复核）';
COMMENT ON COLUMN ai_repurchase_prediction.invoke_log_id IS '关联 ai_invoke_log.log_id（token/费用/耗时真相在日志侧）';
COMMENT ON COLUMN ai_repurchase_prediction.followup_registered IS '是否已登记建跟进（站内登记；M3-08 跟进任务下发为远期 Backlog）';
COMMENT ON COLUMN ai_repurchase_prediction.push_registered IS '是否已登记推送（站内登记；M5-03 营销推送下发为远期 Backlog）';
CREATE INDEX IF NOT EXISTS idx_ai_repurchase_batch_prob ON ai_repurchase_prediction (batch_no, prob DESC);
CREATE INDEX IF NOT EXISTS idx_ai_repurchase_customer    ON ai_repurchase_prediction (customer_id, prediction_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_repurchase_created     ON ai_repurchase_prediction (prediction_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_repurchase_followup    ON ai_repurchase_prediction (followup_registered)
    WHERE followup_registered = TRUE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_repurchase_prediction' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_repurchase_prediction_updated_at') THEN
        CREATE TRIGGER trg_ai_repurchase_prediction_updated_at BEFORE UPDATE ON ai_repurchase_prediction
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END;
$$;
