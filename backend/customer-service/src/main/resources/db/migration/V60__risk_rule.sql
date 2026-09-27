-- ============================================================
-- V60 风控规则表（M3-B6 / DESIGN-M3 §3 M3-17：黑名单与风控切真）
-- 单表：risk_rule（风控规则定义＋启停＋累计命中，规则卡格直读本表）
-- 说明：flyway_schema_history 全库共享，版本号全局递增（V59 后取 V60）。
-- 幂等可重入：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS。
-- 本期切真范围照 mock 活规格：规则列表＋启停 toggle（规则引擎真实命中
--   计算在 txn/事件域后续批，hit_count 本期留存展示值）。
-- ddl-auto=validate 硬约束（B48 卡3 收口）：本 DDL 与 RiskRule 实体映射逐列对齐。
-- ============================================================

CREATE TABLE IF NOT EXISTS risk_rule (
    id          BIGSERIAL    PRIMARY KEY,
    rule_no     VARCHAR(16)  NOT NULL UNIQUE,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) NOT NULL DEFAULT '',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    action      VARCHAR(8)   NOT NULL,
    hit_count   INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_risk_rule_action CHECK (action IN ('BLOCK', 'WARN', 'REVIEW'))
);

COMMENT ON TABLE  risk_rule IS '风控规则（M3-B6 / DESIGN-M3 §3 M3-17）：规则定义＋启停开关＋累计命中；风控页规则卡格直读本表';
COMMENT ON COLUMN risk_rule.rule_no IS '规则编号：RR-数字（种子沿用 mock 字面值 RR-1..RR-5）';
COMMENT ON COLUMN risk_rule.name IS '规则名称（如「非本人会员卡核销」）';
COMMENT ON COLUMN risk_rule.description IS '规则描述（命中口径人类可读）';
COMMENT ON COLUMN risk_rule.enabled IS '启用标记：风控页 switch 启停（risk:edit），false=停用不命中';
COMMENT ON COLUMN risk_rule.action IS '命中动作三值：BLOCK 拦截交易 / WARN 预警提示 / REVIEW 人工审核';
COMMENT ON COLUMN risk_rule.hit_count IS '累计命中次数（规则卡格展示；真实命中累加在 txn/事件域后续批）';
COMMENT ON COLUMN risk_rule.created_at IS '创建时刻';
COMMENT ON COLUMN risk_rule.updated_at IS '最近更新时刻（启停流转刷 now()）';

CREATE INDEX IF NOT EXISTS idx_risk_rule_enabled ON risk_rule (enabled);
