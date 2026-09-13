-- =============================================================================
-- V25__ai_daily_report.sql
-- B47 AI 治理五期·卡4：A1-05 AI 经营日报真实出站与日报/建议/订阅落库
--
-- 版本链：全库共享 flyway_schema_history，V15~V24 为 ai-service，本脚本占用 V25。
-- DDL 全部 IF NOT EXISTS，可在历史 ddl-auto=update 环境重入。
--
-- 三张表：
--   ai_daily_report      一次「生成日报」一行（同日期+门店可重生成多行，读时取最新一版）：
--                        指标来自 txn-service /api/txn/internal/daily-metrics（Asia/Shanghai
--                        自然日真实聚合：已收款营收/到店登记/首单新客/已退款与风控异常），
--                        LLM 经 daily 功能 invoke 全治理链产出经营摘要（不编造无数据源事实），
--                        原文 raw_output 留档，invoke_log_id 关联 append-only 的 ai_invoke_log。
--   ai_daily_suggestion  一份日报多条建议（核心/异常/行动），采纳为站内幂等登记
--                        （真实任务下发为远期 Backlog，与流失干预登记同边界）。
--   ai_daily_subscription 员工级「每日自动推送」偏好登记；本期仅落偏好，四通道无真实定时出站，
--                        推送状态页如实置灰（企微/短信/邮件真实触达 M5-03 为远期 Backlog）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_daily_report (
    report_id               BIGSERIAL     PRIMARY KEY,
    report_date             VARCHAR(16)   NOT NULL,
    store_code              VARCHAR(32)   NOT NULL DEFAULT '',
    revenue_fen             BIGINT        NOT NULL DEFAULT 0,
    paid_order_count        BIGINT        NOT NULL DEFAULT 0,
    arrival_count           BIGINT        NOT NULL DEFAULT 0,
    new_customer_count      BIGINT        NOT NULL DEFAULT 0,
    refund_count            BIGINT        NOT NULL DEFAULT 0,
    refund_fen              BIGINT        NOT NULL DEFAULT 0,
    contra_yellow_count     BIGINT        NOT NULL DEFAULT 0,
    contra_red_count        BIGINT        NOT NULL DEFAULT 0,
    anomaly_count           BIGINT        NOT NULL DEFAULT 0,
    metrics_json            VARCHAR(4000) NOT NULL DEFAULT '{}',
    summary                 VARCHAR(1000) NOT NULL DEFAULT '',
    raw_output              VARCHAR(8000) NOT NULL DEFAULT '',
    invoke_log_id           BIGINT,
    model_code              VARCHAR(128),
    total_tokens            INTEGER,
    cost_fen                BIGINT        NOT NULL DEFAULT 0,
    staff_id                VARCHAR(64),
    staff_name              VARCHAR(64),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now()
);
COMMENT ON TABLE ai_daily_report IS 'AI 经营日报（daily 功能真实出站沉淀；同日期+门店可重生成，读时取最新一版）';
COMMENT ON COLUMN ai_daily_report.report_date IS '日报业务自然日（yyyy-MM-dd，Asia/Shanghai 日界）';
COMMENT ON COLUMN ai_daily_report.store_code IS '门店码（空串=全集团/登录人默认域）';
COMMENT ON COLUMN ai_daily_report.revenue_fen IS '当日已收款订单营收合计（分，交易域真实聚合）';
COMMENT ON COLUMN ai_daily_report.arrival_count IS '当日到店登记行数（arrival.arrived_at 落自然日，不去重）';
COMMENT ON COLUMN ai_daily_report.new_customer_count IS '当日首单新客数（当日成交客户中窗口内首笔已收款落在当日者）';
COMMENT ON COLUMN ai_daily_report.refund_fen IS '当日已完成退款金额合计（分，status=REFUNDED 按 refunded_at）';
COMMENT ON COLUMN ai_daily_report.anomaly_count IS '异常项=已完成退款笔数+当日已收款订单 contraCheck YELLOW/RED 笔数';
COMMENT ON COLUMN ai_daily_report.metrics_json IS '交易域指标原文 JSON（含环比上一自然日，供前端趋势与复核）';
COMMENT ON COLUMN ai_daily_report.summary IS 'LLM 经营摘要（容错解析，解析失败为兜底文案，不编造数据）';
COMMENT ON COLUMN ai_daily_report.raw_output IS '模型输出原文（截断 8000 字，供人工复核）';
COMMENT ON COLUMN ai_daily_report.invoke_log_id IS '关联 ai_invoke_log.log_id（token/费用/耗时真相在日志侧）';
CREATE INDEX IF NOT EXISTS idx_ai_daily_date_store ON ai_daily_report (report_date, store_code, report_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_daily_created    ON ai_daily_report (report_id DESC);

CREATE TABLE IF NOT EXISTS ai_daily_suggestion (
    suggestion_id           BIGSERIAL     PRIMARY KEY,
    report_id               BIGINT        NOT NULL,
    suggestion_type         VARCHAR(8)    NOT NULL DEFAULT 'action',
    title                   VARCHAR(128)  NOT NULL DEFAULT '',
    detail                  VARCHAR(1000) NOT NULL DEFAULT '',
    adopted                 BOOLEAN       NOT NULL DEFAULT FALSE,
    adopted_at              TIMESTAMPTZ,
    adopted_by              VARCHAR(64),
    staff_id                VARCHAR(64),
    staff_name              VARCHAR(64),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_daily_sug_type CHECK (suggestion_type IN ('core', 'anomaly', 'action'))
);
COMMENT ON TABLE ai_daily_suggestion IS 'AI 经营日报建议（一份日报多条；采纳为站内幂等登记，真实任务下发为远期 Backlog）';
COMMENT ON COLUMN ai_daily_suggestion.report_id IS '所属 ai_daily_report.report_id';
COMMENT ON COLUMN ai_daily_suggestion.suggestion_type IS '建议类型：core 核心 / anomaly 异常 / action 行动';
COMMENT ON COLUMN ai_daily_suggestion.adopted IS '是否已采纳为任务（站内登记；真实任务系统下发为远期 Backlog）';
CREATE INDEX IF NOT EXISTS idx_ai_daily_sug_report ON ai_daily_suggestion (report_id, suggestion_id);
CREATE INDEX IF NOT EXISTS idx_ai_daily_sug_adopt  ON ai_daily_suggestion (adopted) WHERE adopted = TRUE;

CREATE TABLE IF NOT EXISTS ai_daily_subscription (
    subscription_id         BIGSERIAL     PRIMARY KEY,
    staff_id                VARCHAR(64)   NOT NULL,
    store_code              VARCHAR(32)   NOT NULL DEFAULT '',
    subscribed              BOOLEAN       NOT NULL DEFAULT FALSE,
    staff_name              VARCHAR(64),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_daily_sub_staff UNIQUE (staff_id)
);
COMMENT ON TABLE ai_daily_subscription IS '经营日报「每日自动推送」员工级偏好（本期仅登记偏好，无真实定时出站，四通道状态如实置灰）';
COMMENT ON COLUMN ai_daily_subscription.staff_id IS '订阅人员工号（登录人，不信入参）';
COMMENT ON COLUMN ai_daily_subscription.subscribed IS '是否订阅每日自动推送（偏好登记；真实推送 M5-03 为远期 Backlog）';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_daily_report' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_daily_report_updated_at') THEN
        CREATE TRIGGER trg_ai_daily_report_updated_at BEFORE UPDATE ON ai_daily_report
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_daily_suggestion' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_daily_suggestion_updated_at') THEN
        CREATE TRIGGER trg_ai_daily_suggestion_updated_at BEFORE UPDATE ON ai_daily_suggestion
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_daily_subscription' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_daily_subscription_updated_at') THEN
        CREATE TRIGGER trg_ai_daily_subscription_updated_at BEFORE UPDATE ON ai_daily_subscription
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END;
$$;
