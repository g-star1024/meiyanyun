-- =============================================================================
-- V27__ai_scheduling_plan.sql
-- B47 AI 治理五期·卡6：A1-08 智能排班周方案与班次槽位落库
--
-- 版本链：全库共享 flyway_schema_history，V15~V26 为 ai-service，本脚本占用 V27。
-- DDL 全部 IF NOT EXISTS，可在历史 ddl-auto=update 环境重入；本脚本不做任何 seed，
-- 周方案全部来自排班页真实生成（下周一起 7 天）。
--
-- 两张表：
--   ai_scheduling_plan  周排班方案头（一周一版，同周+门店可重生成多行，读时取最新）：
--                       预测客流/建议排班人次/缺口/规则估算成本为真实历史到店与组织服务
--                       在职员工池经规则计算的结果；summary/notes 走 scheduling 功能 invoke
--                       全治理链（角色灰度/门店灰度/敏感词/配额/计费/ai_invoke_log）；
--                       采纳仅为站内幂等状态翻转（M2-03 排班后端不存在，真实回填为远期 Backlog）。
--   ai_scheduling_slot  班次槽位行：day_index 0~6（周一~周日）× 三班（MORNING/MID/EVENING），
--                       每行一个"人·班"槽位；员工池不足时 staff_id 留空表示未覆盖缺口槽。
--
-- 诚实口径：历史到店登记为 0 的门店不造预测客流与排班需求；系统内无员工工资数据，
--           slot.cost_fen 为页面明示的岗位参考班薪规则估算（非真实薪资）；现排方案无数据源，
--           不做"现排 vs AI"伪造对比（前端对比页仅展示方案自检指标与缺口）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_scheduling_plan (
    plan_id                 BIGSERIAL     PRIMARY KEY,
    week_start              VARCHAR(16)   NOT NULL,
    store_code              VARCHAR(32)   NOT NULL DEFAULT '',
    status                  VARCHAR(8)    NOT NULL DEFAULT 'DRAFT',
    forecast_total          INTEGER       NOT NULL DEFAULT 0,
    slot_total              INTEGER       NOT NULL DEFAULT 0,
    staff_pool_count        INTEGER       NOT NULL DEFAULT 0,
    gap_slots               INTEGER       NOT NULL DEFAULT 0,
    cost_fen                BIGINT        NOT NULL DEFAULT 0,
    forecast_json           VARCHAR(2000) NOT NULL DEFAULT '[]',
    notes_json              VARCHAR(2000) NOT NULL DEFAULT '[]',
    summary                 VARCHAR(1000) NOT NULL DEFAULT '',
    raw_output              VARCHAR(8000) NOT NULL DEFAULT '',
    invoke_log_id           BIGINT,
    model_code              VARCHAR(128),
    total_tokens            INTEGER,
    llm_cost_fen            BIGINT        NOT NULL DEFAULT 0,
    adopted_at              TIMESTAMPTZ,
    adopted_by              VARCHAR(64),
    staff_id                VARCHAR(64),
    staff_name              VARCHAR(64),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_sched_plan_status CHECK (status IN ('DRAFT', 'ADOPTED'))
);
COMMENT ON TABLE ai_scheduling_plan IS 'AI 智能排班周方案头（真实历史到店+在职员工池规则生成，LLM 仅解读；同周多版本读最新）';
COMMENT ON COLUMN ai_scheduling_plan.week_start IS '方案周一周日 yyyy-MM-dd（Asia/Shanghai，取生成时或之后最近的周一）';
COMMENT ON COLUMN ai_scheduling_plan.store_code IS '门店码；空串为登录人的全部门店/区域视角';
COMMENT ON COLUMN ai_scheduling_plan.status IS 'DRAFT 草稿 / ADOPTED 已采纳（站内幂等登记；M2-03 真实回填为远期 Backlog）';
COMMENT ON COLUMN ai_scheduling_plan.forecast_total IS '下周 7 天预测客流合计（最近 14 天真实到店登记按星期均值）';
COMMENT ON COLUMN ai_scheduling_plan.slot_total IS '建议排班人次合计（3 班 × 7 天需求槽位总数，人·班）';
COMMENT ON COLUMN ai_scheduling_plan.staff_pool_count IS '生成时组织服务返回的在职排班员工数';
COMMENT ON COLUMN ai_scheduling_plan.gap_slots IS '员工池容量不足导致的未覆盖槽位数（每周每人最多 5 班、每天至多 1 班）';
COMMENT ON COLUMN ai_scheduling_plan.cost_fen IS '排班人力成本规则估算（分）；系统无工资数据，按岗位参考班薪×槽位估算，非真实薪资';
COMMENT ON COLUMN ai_scheduling_plan.forecast_json IS '7 天预测明细 JSON（日期/星期/预测客流/该星期历史样本均值）';
COMMENT ON COLUMN ai_scheduling_plan.notes_json IS 'LLM 排班建议 notes JSON（高峰/缺口/公平性，容错解析，失败为规则兜底）';
COMMENT ON COLUMN ai_scheduling_plan.summary IS 'LLM 排班解读摘要（容错解析，失败用真实数值兜底）';
COMMENT ON COLUMN ai_scheduling_plan.raw_output IS '模型输出原文（截断 8000 字）';
COMMENT ON COLUMN ai_scheduling_plan.invoke_log_id IS '关联 ai_invoke_log.log_id（token/费用/耗时真相在日志侧）';
COMMENT ON COLUMN ai_scheduling_plan.llm_cost_fen IS '本次 LLM 调用费用（分，取自 invoke 计费；与规则估算的人力成本 cost_fen 不同口径）';
CREATE INDEX IF NOT EXISTS idx_ai_sched_plan_week ON ai_scheduling_plan (week_start DESC, plan_id DESC);
CREATE INDEX IF NOT EXISTS idx_ai_sched_plan_store ON ai_scheduling_plan (store_code, week_start, plan_id DESC);

CREATE TABLE IF NOT EXISTS ai_scheduling_slot (
    slot_id                 BIGSERIAL     PRIMARY KEY,
    plan_id                 BIGINT        NOT NULL,
    day_index               INTEGER       NOT NULL,
    shift_code              VARCHAR(8)    NOT NULL,
    staff_id                VARCHAR(64)   NOT NULL DEFAULT '',
    staff_name              VARCHAR(64),
    role_code               VARCHAR(64),
    role_name               VARCHAR(64),
    hours                   INTEGER       NOT NULL DEFAULT 6,
    cost_fen                BIGINT        NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_ai_sched_slot_plan FOREIGN KEY (plan_id)
        REFERENCES ai_scheduling_plan (plan_id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_sched_slot_day CHECK (day_index BETWEEN 0 AND 6),
    CONSTRAINT chk_ai_sched_slot_shift CHECK (shift_code IN ('MORNING', 'MID', 'EVENING'))
);
COMMENT ON TABLE ai_scheduling_slot IS 'AI 排班班次槽位（day_index 0~6 × 三班，每行一人·班；staff_id 为空即员工池未覆盖的缺口槽）';
COMMENT ON COLUMN ai_scheduling_slot.plan_id IS '所属 ai_scheduling_plan.plan_id';
COMMENT ON COLUMN ai_scheduling_slot.day_index IS '0=周一 … 6=周日';
COMMENT ON COLUMN ai_scheduling_slot.shift_code IS 'MORNING 早班 09-15 / MID 中班 12-18 / EVENING 晚班 15-21';
COMMENT ON COLUMN ai_scheduling_slot.staff_id IS '排到该槽的真实在职工号；员工池容量不足时为空串（缺口槽）';
COMMENT ON COLUMN ai_scheduling_slot.hours IS '单班工时（固定 6 小时）';
COMMENT ON COLUMN ai_scheduling_slot.cost_fen IS '该槽岗位参考班薪规则估算（分）；缺口槽为 0';
CREATE INDEX IF NOT EXISTS idx_ai_sched_slot_plan ON ai_scheduling_slot (plan_id, slot_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_scheduling_plan' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_sched_plan_updated_at') THEN
        CREATE TRIGGER trg_ai_sched_plan_updated_at BEFORE UPDATE ON ai_scheduling_plan
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END;
$$;
