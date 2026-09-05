-- V5__card_ledger.sql
-- 美研云门店中台 - B4 充值/储值台账（customer 域 card_ledger 储值卡流水表）
-- 版本号说明：共享库 flyway_schema_history 中 V4 已被 finance-service 的 V4__finance_fund_entry.sql
--   占用（B3 批次），故本迁移用 V5，避免同版本号 checksum 冲突。
-- 创建时间: 2026-09-05
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   B4 起客户域开通会员卡储值能力：收银/店长/超管对「在用」会员卡充值（现金/银行卡/微信/支付宝，
--   充值不可用储值余额），交易域 balance 支付经内部端点实扣，退卡终审联动清零退卡。
--   card_ledger 为 append-only 流水台账（照 points_ledger 同哲学：只 INSERT，禁 UPDATE/DELETE），
--   member_card.balance 为该卡流水的累计快照，对账恒等式：Σ card_ledger.amount = member_card.balance。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + 索引 IF NOT EXISTS，可重入；
-- 迁移后 customer 的 JPA ddl-auto=update 对 card_ledger 全部 no-op（列已齐全）。

-- ============================================================
-- 1. card_ledger 储值卡流水（append-only，JPA 实体同名映射）
-- ============================================================
CREATE TABLE IF NOT EXISTS card_ledger (
    ledger_id       BIGSERIAL     PRIMARY KEY,
    card_no         VARCHAR(24)   NOT NULL,                -- 会员卡卡号（member_card.card_no）
    customer_id     VARCHAR(16)   NOT NULL,                -- 客户编号（冗余自 member_card，与 customer.customer_id 同宽）
    change_type     VARCHAR(16)   NOT NULL,                -- RECHARGE 充值 / CONSUME 消费扣额 / REFUND 退卡 / ADJUST 调整
    amount          BIGINT        NOT NULL,                -- 变动额（分；充值为正，消费/退卡为负）
    balance_after   BIGINT        NOT NULL,                -- 变动后卡余额（分，对账锚点）
    biz_ref         VARCHAR(24),                           -- 来源单号：充值 RC 单号 / 订单号 / 退卡 RF-CC 号
    operator        VARCHAR(24),                           -- 经办人（员工编号；内部系统动账记 system）
    store_code      VARCHAR(16),                           -- 门店码
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_card_ledger_change_type CHECK (change_type IN ('RECHARGE','CONSUME','REFUND','ADJUST'))
);
CREATE INDEX IF NOT EXISTS idx_card_ledger_card_no ON card_ledger(card_no);
CREATE INDEX IF NOT EXISTS idx_card_ledger_customer ON card_ledger(customer_id);
CREATE INDEX IF NOT EXISTS idx_card_ledger_biz_ref ON card_ledger(biz_ref);
