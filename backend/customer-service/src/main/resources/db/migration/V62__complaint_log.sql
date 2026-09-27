-- ============================================================
-- V62 客诉时间线表（M3-B8 / DESIGN-M3 §3 M3-20：投诉处理留痕）
-- 单表：complaint_log（complaint 的逐步操作时间线，API 组装为前端 timeline 契约）
-- 幂等可重入：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS。
-- 不建物理外键：complaint_id 逻辑引用 complaint.id（读侧组装，删单不级联）。
-- 命名注记：前端契约字段 `by` 系 SQL 保留字 → 列名 by_name，API 序列化映射回 `by`；
--   前端契约字段 `at` → 列名 at_time，API 序列化映射回 `at`。
-- ddl-auto=validate 硬约束：本 DDL 与 ComplaintLog 实体映射逐列对齐。
-- ============================================================

CREATE TABLE IF NOT EXISTS complaint_log (
    id            BIGSERIAL    PRIMARY KEY,
    complaint_id  BIGINT       NOT NULL,
    at_time       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    by_name       VARCHAR(64)  NOT NULL,
    action        VARCHAR(32)  NOT NULL,
    note          TEXT
);

COMMENT ON TABLE  complaint_log IS '客诉时间线（M3-B8）：登记/受理/提交处理方案/审批结案/退回补充/驳回逐步留痕；列表组装按 at_time,id 升序';
COMMENT ON COLUMN complaint_log.complaint_id IS '客诉单逻辑引用（complaint.id，无物理外键）';
COMMENT ON COLUMN complaint_log.at_time IS '操作时刻（前端契约字段 at）';
COMMENT ON COLUMN complaint_log.by_name IS '操作人（前端契约字段 by；by 系 SQL 保留字故列名 by_name）';
COMMENT ON COLUMN complaint_log.action IS '动作中文名：登记投诉/受理投诉/提交处理方案/审批结案/退回补充处理/驳回投诉';
COMMENT ON COLUMN complaint_log.note IS '备注（医疗风险标记/赔付金额与层级/退回原因/驳回原因等，可空）';

CREATE INDEX IF NOT EXISTS idx_complaint_log_complaint_id ON complaint_log (complaint_id);
