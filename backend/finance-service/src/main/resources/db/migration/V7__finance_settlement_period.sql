-- V7__finance_settlement_period.sql
-- 美研云门店中台 - B7 封账（settlement）期间锁表
-- 创建时间: 2026-09-06
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   DESIGN §9.2 日结/月结封账。fund_entry 分录本就 append-only 不可改（无 status 列），
--   「封账」的实质不是把分录置 SETTLED，而是【阻断新分录落入已闭期间】：封账后任何
--   occurredAt 落在该期间的新分录一律 422 拒绝（含经营域补录/重投），差错只能走 ADJUST
--   调平分录（occurredAt=当前时间，落当前期间，天然不撞闭期）。
--
--   封账维度：期间类型 DAY（日结，period_key=yyyy-MM-dd）/ MONTH（月结，period_key=yyyy-MM）
--   × 门店。期间口径与 fund_entry 一致（occurred_at 按 UTC 归一；账账 UTC 半开区间）。
--   封账为永久动作，不提供解封（红线：已封账不可篡改；如需更正走当前期间 ADJUST）。
--
-- 幂等：CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS，可重入；
-- 重复封账由服务层按 (period_type, period_key, store_code) 唯一约束幂等重放。

CREATE TABLE IF NOT EXISTS settlement_period (
    settlement_id   BIGSERIAL     PRIMARY KEY,
    period_type     VARCHAR(8)    NOT NULL,                -- DAY 日结 / MONTH 月结
    period_key      VARCHAR(10)   NOT NULL,                -- DAY: yyyy-MM-dd；MONTH: yyyy-MM（UTC 归一）
    store_code      VARCHAR(16)   NOT NULL,                -- 门店码（数据域隔离）
    status          VARCHAR(8)    NOT NULL DEFAULT 'CLOSED', -- CLOSED 已封账（永久，无解封）
    entry_count     INTEGER       NOT NULL DEFAULT 0,      -- 封账时点该期间分录笔数（快照）
    net_amount      BIGINT        NOT NULL DEFAULT 0,      -- 封账时点净额快照（分，IN 正 OUT 负）
    memo            VARCHAR(128),                          -- 封账备注（中文）
    closed_by       VARCHAR(64)   NOT NULL,                -- 封账操作人员工号
    closed_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),  -- 封账时间
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_settlement_period UNIQUE (period_type, period_key, store_code),
    CONSTRAINT chk_settlement_period_type CHECK (period_type IN ('DAY','MONTH')),
    CONSTRAINT chk_settlement_period_status CHECK (status IN ('CLOSED'))
);
CREATE INDEX IF NOT EXISTS idx_settlement_period_store ON settlement_period(store_code);
CREATE INDEX IF NOT EXISTS idx_settlement_period_key ON settlement_period(period_type, period_key);
