-- V4__finance_fund_entry.sql
-- 美研云门店中台 - B3 合规写落账（finance 域资金分录 + outbox 英文状态机迁移）
-- 创建时间: 2026-09-05
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   B3 起 finance 首次引入「合规写」：经营域（txn）收款/退款终审/划扣完成后，
--   经内部端点 POST /api/finance/internal/entries 落资金分录 fund_entry（幂等），
--   并逐笔登记 outbox_record 对账台账（PENDING→RECONCILED/DIFF/ADJUSTED）。
--   既有 outbox_record 是 M6 只读镜像期手工预置表（0 行），带中文枚举 CHECK：
--     channel IN ('微信','支付宝','储值','现金')、status IN ('已投递','已对账','差异')，
--   与 B3 英文状态机/英文渠道码不兼容（RECONCILED/ADJUSTED 超 varchar(8)），
--   Hibernate update 不改 CHECK，故由本迁移统一放行（状态机校验收归服务层，避免约束漂移）。
--
-- 幂等：fund_entry IF NOT EXISTS；outbox 约束 DROP IF EXISTS，可重入。
-- 迁移后 finance 的 JPA ddl-auto=update 对 fund_entry/outbox_record 全部 no-op（列已齐全）。

-- ============================================================
-- 1. fund_entry 资金分录（合规账本，系统表，JPA 实体同名映射）
-- ============================================================
CREATE TABLE IF NOT EXISTS fund_entry (
    entry_id        BIGSERIAL     PRIMARY KEY,
    idem_key        VARCHAR(64)   NOT NULL,                -- 幂等键（经营域事件维度唯一，重复投递不双算）
    biz_ref         VARCHAR(48)   NOT NULL,                -- 业务单据号（订单/退款/划扣单号）
    biz_type        VARCHAR(16)   NOT NULL,                -- ORDER/REFUND/WRITEOFF/RECHARGE/ADJUST
    subject         VARCHAR(16)   NOT NULL,                -- RF-REVENUE/RF-REFUND/RF-DEPOSIT
    direction       VARCHAR(4)    NOT NULL,                -- IN/OUT
    amount          BIGINT        NOT NULL,                -- 金额（分，始终为正；方向由 direction 表达）
    channel         VARCHAR(8),                            -- 渠道码 cash/card/wxpay/alipay/balance/transfer；内部结转为 NULL
    source          VARCHAR(8)    NOT NULL,                -- CASHIER 收银 / ERP 内部结转
    ref_type        VARCHAR(16)   NOT NULL,                -- ORDER/REFUND/WRITEOFF/RECHARGE/ADJUST
    store_code      VARCHAR(16)   NOT NULL,                -- 门店码（数据域隔离）
    memo            VARCHAR(128),                          -- 摘要（中文）
    occurred_at     TIMESTAMPTZ   NOT NULL,                -- 业务发生时间
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_fund_entry_idem_key UNIQUE (idem_key),
    CONSTRAINT chk_fund_entry_direction CHECK (direction IN ('IN','OUT')),
    CONSTRAINT chk_fund_entry_amount CHECK (amount > 0)
);
CREATE INDEX IF NOT EXISTS idx_fund_entry_biz_ref ON fund_entry(biz_ref);
CREATE INDEX IF NOT EXISTS idx_fund_entry_occurred ON fund_entry(occurred_at);
CREATE INDEX IF NOT EXISTS idx_fund_entry_store ON fund_entry(store_code);

-- ============================================================
-- 2. outbox_record 对账台账：中文枚举 CHECK → 英文状态机
-- ============================================================
-- status 扩长（RECONCILED=10、ADJUSTED=8），默认 PENDING
ALTER TABLE outbox_record ALTER COLUMN status TYPE VARCHAR(16);
ALTER TABLE outbox_record ALTER COLUMN status SET DEFAULT 'PENDING';
-- channel 放行英文渠道码（cash/card/wxpay/alipay/balance/transfer）与内部结转 NULL
ALTER TABLE outbox_record ALTER COLUMN channel DROP NOT NULL;
ALTER TABLE outbox_record ALTER COLUMN channel TYPE VARCHAR(16);
-- 移除 M6 镜像期中文枚举约束（状态机/渠道校验由服务层白名单保证）
ALTER TABLE outbox_record DROP CONSTRAINT IF EXISTS outbox_record_channel_check;
ALTER TABLE outbox_record DROP CONSTRAINT IF EXISTS outbox_record_status_check;
-- 存量 M6 镜像期中文状态种子数据归一到英文状态机（已投递=待对账 PENDING / 已对账=RECONCILED）
UPDATE outbox_record SET status = 'PENDING'    WHERE status = '已投递';
UPDATE outbox_record SET status = 'RECONCILED' WHERE status = '已对账';
-- 英文状态机约束（PENDING 待对账 / RECONCILED 已对账 / DIFF 差异 / ADJUSTED 已调平）
ALTER TABLE outbox_record ADD CONSTRAINT outbox_record_status_check
    CHECK (status IN ('PENDING','RECONCILED','DIFF','ADJUSTED'));
