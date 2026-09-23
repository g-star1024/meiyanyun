-- ============================================================================
-- V47 · P5-B89 转介绍活动：referral_campaign / referral_campaign_config 两表
--
-- 版本号说明：flyway_schema_history 全库共享，版本号全局递增，本迁移取 V47。
-- 创建时间：2026-09-23
-- 数据库：PostgreSQL 15+
--
-- 背景（DESIGN-P5-B89 定案）：
--   D1 活动实体域归属 customer-service；D2 奖励自动发放链路 v1 仅配置持久化；
--   D3 KPI「累计邀请人数」走 referral 纯真实聚合；D4 活动 invited/converted
--   统计列 v1 仅实体＋种子（聚合留 v2，见 04-backlog v2 登记行）。
--
-- 设计约束（对齐 V44）：
--   1. 不建物理外键，跨表一律逻辑引用；
--   2. 幂等可重入：CREATE TABLE/INDEX IF NOT EXISTS；
--   3. 迁移后 JPA ddl-auto=update 对新表全部 no-op；
--   4. ladders/levels 用 TEXT 存 JSON 文本（MemberLevel.benefits / Customer.concerns
--      全仓先例；DESIGN §3.2 之 jsonb 描述以该先例口径落地，端点契约不受影响）。
-- ============================================================================

-- ── 1. 转介绍活动 ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS referral_campaign (
    campaign_id  VARCHAR(24)  PRIMARY KEY,
    name         VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    start_at     DATE         NOT NULL,
    end_at       DATE         NOT NULL,
    store_code   VARCHAR(16),
    remark       VARCHAR(256),
    created_by   VARCHAR(32),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_referral_campaign_status CHECK (status IN ('DRAFT','ONGOING','ENDED'))
);

COMMENT ON TABLE  referral_campaign            IS '转介绍活动（P5-B89）：活动侧四 mock 块后端化之地基；状态 DRAFT/ONGOING/ENDED';
COMMENT ON COLUMN referral_campaign.store_code IS '门店码：NULL=全部门店（连锁活动），填值=单店活动';
COMMENT ON COLUMN referral_campaign.start_at   IS '开始日期（活规格 startAt，yyyy-MM-dd）';
COMMENT ON COLUMN referral_campaign.end_at     IS '结束日期（活规格 endAt，yyyy-MM-dd）';

CREATE INDEX IF NOT EXISTS idx_rc_status ON referral_campaign(status);
CREATE INDEX IF NOT EXISTS idx_rc_store  ON referral_campaign(store_code);

-- ── 2. 邀请机制全局配置（单行 GLOBAL，point_rule 先例）──────────────────────
CREATE TABLE IF NOT EXISTS referral_campaign_config (
    config_id   VARCHAR(16)  PRIMARY KEY,
    reward_type VARCHAR(16)  NOT NULL DEFAULT 'CASH',
    valid_days  INTEGER      NOT NULL DEFAULT 30,
    script      TEXT         NOT NULL DEFAULT '',
    ladders     TEXT         NOT NULL DEFAULT '[]',
    levels      TEXT         NOT NULL DEFAULT '[]',
    updated_by  VARCHAR(32),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_rcc_config_id   CHECK (config_id = 'GLOBAL'),
    CONSTRAINT chk_rcc_reward_type CHECK (reward_type IN ('POINTS','COUPON','CASH')),
    CONSTRAINT chk_rcc_valid_days  CHECK (valid_days >= 1)
);

COMMENT ON TABLE  referral_campaign_config             IS '邀请机制全局配置（P5-B89，单行 GLOBAL）：奖励形式/有效期/话术/阶梯奖励/层级奖励';
COMMENT ON COLUMN referral_campaign_config.reward_type IS '基础奖励形式（前端词表）：POINTS 积分 / COUPON 优惠券 / CASH 现金';
COMMENT ON COLUMN referral_campaign_config.ladders     IS '阶梯奖励 JSON 文本：[{threshold,type,amount,desc}]，amount 口径为元（v2 落 reward ×100）';
COMMENT ON COLUMN referral_campaign_config.levels      IS '层级奖励 JSON 文本：[{level,rate,desc}]，rate 0~1';
COMMENT ON COLUMN referral_campaign_config.script      IS '邀请话术';
