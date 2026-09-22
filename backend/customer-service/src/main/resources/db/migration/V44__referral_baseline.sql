-- ============================================================================
-- V44 · P5-B86 转介绍地基：referral / referral_reward 两表 + EXPIRED 字典补项
--
-- 版本号说明：flyway_schema_history 全库共享，版本号全局递增，本迁移取 V44。
-- 创建时间：2026-09-23
-- 数据库：PostgreSQL 15+
--
-- 背景（DESIGN-P5-B86 定案）：
--   D1-A 仅转介绍一页；D2-A 落 customer 域；
--   D3-A EXPIRED 独立终态 + 释放绑定（过期/拒绝后被推荐人可被重新推荐）；
--   D4-A 奖励 v1 手动登记（积分/赠金/券自动发放留 v2，idem_key 挂钩点预留）。
--
-- 设计约束：
--   1. 不建物理外键，跨表一律逻辑引用（对齐 customer_merge 等既有表惯例）；
--   2. 幂等可重入：CREATE TABLE/INDEX IF NOT EXISTS、字典 INSERT WHERE NOT EXISTS；
--   3. 迁移后 JPA ddl-auto=update 对新表全部 no-op；
--   4. uk_referral_referee_active 部分唯一索引：同一被推荐人在进行态
--      （PENDING/CONFIRMED/VISITED/DEAL）仅允许一条绑定，EXPIRED/REJECTED 后释放。
-- ============================================================================

-- ── 1. REFERRAL_STATUS 补 EXPIRED（已确认/已接受等七态见 V2，本项为第八态）──
INSERT INTO sys_dictionary (category, dict_code, dict_value, dict_label, dict_color, dict_icon, sort_order, is_enabled, created_by)
SELECT 'REFERRAL_STATUS', 'REFERRAL_STATUS_EXPIRED', 'EXPIRED', '已过期', 'default', NULL, 8, TRUE, 0
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dictionary WHERE category = 'REFERRAL_STATUS' AND dict_value = 'EXPIRED'
);

-- ── 2. 转介绍关系单 ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS referral (
    referral_id          VARCHAR(24)  PRIMARY KEY,
    referrer_customer_id VARCHAR(16)  NOT NULL,
    referee_customer_id  VARCHAR(16)  NOT NULL,
    campaign_id          VARCHAR(24),
    status               VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    valid_days           INTEGER      NOT NULL DEFAULT 30,
    bound_at             TIMESTAMPTZ  NOT NULL,
    expire_at            TIMESTAMPTZ  NOT NULL,
    confirmed_at         TIMESTAMPTZ,
    visited_at           TIMESTAMPTZ,
    deal_at              TIMESTAMPTZ,
    expired_at           TIMESTAMPTZ,
    rejected_at          TIMESTAMPTZ,
    reject_reason        VARCHAR(128),
    deal_amount_cents    BIGINT,
    store_code           VARCHAR(16)  NOT NULL,
    remark               VARCHAR(256),
    created_by           VARCHAR(32),
    client_token         VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_referral_status CHECK (status IN ('PENDING','CONFIRMED','VISITED','DEAL','EXPIRED','REJECTED'))
);

COMMENT ON TABLE  referral                      IS '转介绍关系单（P5-B86）：状态机 PENDING→CONFIRMED→VISITED→DEAL；PENDING→REJECTED；PENDING/CONFIRMED→EXPIRED(Job)';
COMMENT ON COLUMN referral.referrer_customer_id IS '推荐人客户号（逻辑引用 customer.customer_id，不建物理外键）';
COMMENT ON COLUMN referral.referee_customer_id  IS '被推荐人客户号（逻辑引用 customer.customer_id）';
COMMENT ON COLUMN referral.deal_amount_cents    IS '成交金额（分）';
COMMENT ON COLUMN referral.client_token         IS '创建幂等令牌：重放直接返回原单';

-- 进行态唯一绑定：EXPIRED/REJECTED 后释放，可被重新推荐（D3-A）
CREATE UNIQUE INDEX IF NOT EXISTS uk_referral_referee_active
    ON referral(referee_customer_id)
    WHERE status IN ('PENDING','CONFIRMED','VISITED','DEAL');

CREATE INDEX IF NOT EXISTS idx_referral_referrer ON referral(referrer_customer_id);
CREATE INDEX IF NOT EXISTS idx_referral_status   ON referral(status);
CREATE INDEX IF NOT EXISTS idx_referral_store    ON referral(store_code);
CREATE INDEX IF NOT EXISTS idx_referral_expire   ON referral(expire_at);

-- ── 3. 奖励登记（v1 手动登记，idem_key 预留 v2 自动发放挂钩点）──────────────
CREATE TABLE IF NOT EXISTS referral_reward (
    reward_id      VARCHAR(24)  PRIMARY KEY,
    referral_id    VARCHAR(24)  NOT NULL,
    reward_type    VARCHAR(16)  NOT NULL,
    trigger_event  VARCHAR(16)  NOT NULL,
    amount_cents   BIGINT,
    points         BIGINT,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    granted_by     VARCHAR(32),
    granted_at     TIMESTAMPTZ,
    idem_key       VARCHAR(80)  NOT NULL,
    remark         VARCHAR(256),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_referral_reward_idem UNIQUE (idem_key),
    CONSTRAINT chk_referral_reward_status CHECK (status IN ('PENDING','GRANTED','REJECTED')),
    CONSTRAINT chk_referral_reward_type   CHECK (reward_type IN ('POINT','GRANT','COUPON','COMMISSION')),
    CONSTRAINT chk_referral_reward_event  CHECK (trigger_event IN ('CONFIRMED','VISITED','DEAL'))
);

COMMENT ON TABLE  referral_reward             IS '转介绍奖励登记（P5-B86）：v1 手动确认发放，idem_key={referralId}:{triggerEvent}:{rewardType}';
COMMENT ON COLUMN referral_reward.referral_id IS '转介绍单号（逻辑引用 referral.referral_id，不建物理外键）';

CREATE INDEX IF NOT EXISTS idx_referral_reward_referral ON referral_reward(referral_id);
CREATE INDEX IF NOT EXISTS idx_referral_reward_status   ON referral_reward(status);
