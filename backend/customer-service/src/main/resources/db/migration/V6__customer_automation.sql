-- V6__customer_automation.sql
-- 美研云门店中台 - 域①客户域自动化（标签自动化规则 + 消费自动积分游标）
-- 版本号说明：共享库 flyway_schema_history 中 V4 已被 finance-service 占用（B3 批次），
--   V5 为 card_ledger（B4 批次），本迁移用 V6，避免同版本号 checksum 冲突。
-- 创建时间: 2026-09-09
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   P5-B25 客户域自动化三件套——
--   1) 标签自动化规则 tag_auto_rule：运营配置「条件→自动打/撤标」，由 TagAutoRuleJob 定时扫描执行；
--   2) 消费自动积分游标 auto_points_state：单行记录上次扫描窗口上界，保证积分引擎断点续跑、不重复发分。
--
-- 幂等：CREATE TABLE IF NOT EXISTS，可重入；迁移后 JPA ddl-auto=update 对这两张表全部 no-op（列已齐全）。

-- ============================================================
-- 1. tag_auto_rule 标签自动化规则
-- ============================================================
CREATE TABLE IF NOT EXISTS tag_auto_rule (
    rule_id                  VARCHAR(16)   PRIMARY KEY,            -- TA###
    name                    VARCHAR(64)   NOT NULL,
    enabled                 BOOLEAN       NOT NULL DEFAULT TRUE,
    effect                  VARCHAR(8)    NOT NULL,                -- ASSIGN 打标 / REVOKE 撤标
    target_tag_id           VARCHAR(16)   NOT NULL,               -- 目标标签（customer_tag.tag_id）
    condition_type          VARCHAR(16)   NOT NULL,               -- CONSUME_GTE/VISIT_GTE/POINTS_GTE/LEVEL_IN/CHANNEL_EQ
    condition_value         VARCHAR(128),                          -- 数值/逗号清单
    priority                INTEGER       NOT NULL DEFAULT 100,    -- 执行优先级（升序先执行）
    revoke_when_unsatisfied BOOLEAN       NOT NULL DEFAULT FALSE,  -- ASSIGN 时条件不满足是否自动撤标
    last_run_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_tag_auto_rule_effect CHECK (effect IN ('ASSIGN','REVOKE')),
    CONSTRAINT chk_tag_auto_rule_cond CHECK (
        condition_type IN ('CONSUME_GTE','VISIT_GTE','POINTS_GTE','LEVEL_IN','CHANNEL_EQ'))
);
CREATE INDEX IF NOT EXISTS idx_tag_auto_rule_enabled ON tag_auto_rule(enabled, priority);

-- ============================================================
-- 2. auto_points_state 消费自动积分游标（单行 state_id=1）
-- ============================================================
CREATE TABLE IF NOT EXISTS auto_points_state (
    state_id              INTEGER       PRIMARY KEY,              -- 固定 1
    last_run_at           TIMESTAMPTZ,                            -- 上次扫描窗口上界（NULL=从未运行，首次全量回填）
    last_scanned          BIGINT,                                -- 上次扫描订单数（含发/退）
    last_awarded          BIGINT,                                -- 上次实际发放积分
    last_refunded_points  BIGINT,                                -- 上次实际回退积分（绝对值）
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);
