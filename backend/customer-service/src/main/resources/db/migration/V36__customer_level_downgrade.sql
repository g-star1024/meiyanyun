-- V36__customer_level_downgrade.sql
-- 美研云门店中台 - 域①客户域会员自动降级（月消费事实表 + 等级变更历史 + 月聚合游标 + 自动降级开关）
-- 版本号说明：全库共享 flyway_schema_history 全局递增版本号——V34 external integration、
--   V35 org notify global quiet 均已占用（meiyun_seed 运行态核实：35 notify global quiet success=t），
--   故本迁移取首个空号 V36，避免同版本号/checksum 冲突。
-- 创建时间: 2026-09-18
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   P5-B62 卡1 会员自动降级引擎——
--   1) customer_monthly_spend 客户月消费事实表：逐月从交易域拉取已收款/已退款（UTC 月窗），
--      net_fen = paid_fen - refund_fen，作为升降级判定的唯一事实真源（替代无运行时维护的 total_spend 死字段）；
--   2) customer_level_history 等级变更历史：LEVEL_INIT 基线补种 / AUTO_UPGRADE / AUTO_DOWNGRADE / MANUAL
--      四类来源统一留痕，降级保护期自最近进入当前等级的月份起算；
--   3) monthly_spend_state 月聚合游标（单行 state_id=1）：last_closed_month 只推进到已闭合自然月，在跑月绝不聚合；
--   4) level_rule_config.auto_downgrade：自动降级总开关（与 auto_upgrade 独立）。
--
-- 设计约束：
--   - 三表均不建物理外键（对齐 points_ledger/card_ledger append-only 哲学，兼容 DSAR 匿名化与后续撞单合并引用迁移）；
--   - customer 业务表系 JPA ddl-auto=update 派生，Flyway 先于 Hibernate 执行，故本迁移严禁 INSERT customer/
--     member_level 相关数据，LEVEL_INIT 基线由 Java 侧 LevelInitService 幂等补种；
--   - 幂等：CREATE TABLE/INDEX/ADD COLUMN 全部 IF NOT EXISTS，可重入；迁移后 ddl-auto=update 对新表全部 no-op（列已齐全）。

-- ============================================================
-- 1. customer_monthly_spend 客户月消费事实（分；按客户×自然月唯一，upsert 覆盖重算）
-- ============================================================
CREATE TABLE IF NOT EXISTS customer_monthly_spend (
    id            BIGSERIAL     PRIMARY KEY,
    customer_id   VARCHAR(16)   NOT NULL,                -- 客户编号（customer.customer_id，逻辑引用不建物理 FK）
    period_month  CHAR(7)       NOT NULL,                -- 自然月 2026-08（UTC 月窗）
    paid_fen      BIGINT        NOT NULL DEFAULT 0,      -- 当月已收款合计（分，剔除 CARD_SALE 储值购卡）
    refund_fen    BIGINT        NOT NULL DEFAULT 0,      -- 当月已退款合计（分，退卡 txn_card_cancel 不入窗）
    net_fen       BIGINT        NOT NULL DEFAULT 0,      -- 当月净消费 = paid - refund（可为负）
    store_code    VARCHAR(16),                           -- 聚合时客户主档门店码快照（公海客户为空）
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_monthly_spend UNIQUE (customer_id, period_month)
);
CREATE INDEX IF NOT EXISTS idx_customer_monthly_spend_month ON customer_monthly_spend(period_month);

-- ============================================================
-- 2. customer_level_history 会员等级变更历史（append-only）
-- ============================================================
CREATE TABLE IF NOT EXISTS customer_level_history (
    id             BIGSERIAL     PRIMARY KEY,
    customer_id    VARCHAR(16)   NOT NULL,               -- 客户编号（逻辑引用不建物理 FK）
    from_level     VARCHAR(8),                           -- 原等级（LEVEL_INIT 基线行为 NULL）
    to_level       VARCHAR(8)    NOT NULL,              -- 新等级（member_level.level 中文名）
    change_source  VARCHAR(16)   NOT NULL,              -- LEVEL_INIT/AUTO_UPGRADE/AUTO_DOWNGRADE/MANUAL
    reason         VARCHAR(64),                          -- 变更原因/审计摘要（≤64 字，对齐手工调级约束）
    changed_at     TIMESTAMPTZ   NOT NULL,              -- 变更生效时间
    CONSTRAINT chk_level_history_source CHECK (
        change_source IN ('LEVEL_INIT','AUTO_UPGRADE','AUTO_DOWNGRADE','MANUAL'))
);
CREATE INDEX IF NOT EXISTS idx_customer_level_history_customer
    ON customer_level_history(customer_id, changed_at);

-- ============================================================
-- 3. monthly_spend_state 月消费聚合游标（单行 state_id=1）
-- ============================================================
CREATE TABLE IF NOT EXISTS monthly_spend_state (
    state_id           INTEGER     PRIMARY KEY,         -- 固定 1
    last_closed_month  CHAR(7),                          -- 已聚合的最近闭合自然月（NULL=从未运行，首次从 2025-01 回填）
    last_run_at        TIMESTAMPTZ,                      -- 上次成功运行时间
    last_paid_orders   BIGINT,                           -- 上次运行拉取的已收款单数
    last_refund_count  BIGINT,                           -- 上次运行拉取的已退款单数
    last_upsert_rows   BIGINT,                           -- 上次运行 upsert 的事实行数
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- 4. level_rule_config 增加自动降级开关（默认开启，与 auto_upgrade 独立）
-- ============================================================
ALTER TABLE level_rule_config ADD COLUMN IF NOT EXISTS auto_downgrade BOOLEAN;
