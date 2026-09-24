-- ============================================================================
-- V51 · P5-B92 内容生产纵深：短视频上下架状态列 + 成交归属登记 + 回写游标
--
-- 版本号说明：flyway_schema_history 全库共享，版本号全局递增，本迁移取 V51。
-- 创建时间：2026-09-24
-- 数据库：PostgreSQL 15+
--
-- 背景（DESIGN-P5-B92 §2 D4/D5/D11）：
--   ① 短视频由「只读」升级为可写三端点（发布/编辑/上下架），需 status 列；
--   ② 售卡/零售单可带成交来源（直播场次/短视频），由 DealBackfillJob 轮询
--      txn 已收款/已退款投影回写场次/视频成交计数，需归属登记表（幂等锚
--      order_no）与单行游标表（镜像 auto_grant_state）。
--
-- 设计约束（对齐 V44/V47/V50）：
--   1. 不建物理外键，跨表一律逻辑引用（source_id → live_session/short_video）；
--   2. 幂等可重入：ADD COLUMN / CREATE TABLE / CREATE INDEX IF NOT EXISTS、
--      INSERT ... ON CONFLICT DO NOTHING；
--   3. 迁移后 JPA ddl-auto=validate 对三对象仅校验不改表；
--   4. POSTER 词表预留（D9：海报成交回写本期不做，登记 04 Backlog）。
-- ============================================================================

-- ① 短视频上下架状态列（存量行 DEFAULT 回填 PUBLISHED）
ALTER TABLE short_video
    ADD COLUMN IF NOT EXISTS status VARCHAR(12) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE short_video
    DROP CONSTRAINT IF EXISTS chk_short_video_status;
ALTER TABLE short_video
    ADD CONSTRAINT chk_short_video_status CHECK (status IN ('PUBLISHED', 'OFFLINE'));

COMMENT ON COLUMN short_video.status
    IS '上下架状态（P5-B92）：PUBLISHED 已发布 / OFFLINE 已下架；存量种子 DEFAULT 回填';

-- ② 成交归属登记表（幂等锚 order_no；一笔订单至多归属一次，重放静默吞掉）
CREATE TABLE IF NOT EXISTS mkt_deal_attribution (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_no     VARCHAR(40)  NOT NULL,
    source_type  VARCHAR(16)  NOT NULL,
    source_id    VARCHAR(24)  NOT NULL,
    amount       BIGINT       NOT NULL,
    status       VARCHAR(10)  NOT NULL DEFAULT 'PAID',
    paid_at      TIMESTAMPTZ  NOT NULL,
    refunded_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_mkt_deal_attr_order   UNIQUE (order_no),
    CONSTRAINT chk_mkt_deal_attr_type   CHECK (source_type IN ('LIVE_SESSION', 'SHORT_VIDEO', 'POSTER')),
    CONSTRAINT chk_mkt_deal_attr_status CHECK (status IN ('PAID', 'REFUNDED'))
);
CREATE INDEX IF NOT EXISTS idx_mkt_deal_attr_source
    ON mkt_deal_attribution (source_type, source_id);

COMMENT ON TABLE  mkt_deal_attribution            IS '成交归属登记（P5-B92）：订单→直播场次/短视频归属，DealBackfillJob 写入，order_no 幂等锚';
COMMENT ON COLUMN mkt_deal_attribution.amount     IS '成交额（分），以 txn paid-orders 快照为准';
COMMENT ON COLUMN mkt_deal_attribution.status     IS 'PAID 已收款归属 / REFUNDED 已退款冲销（负向增量已同步场次/视频计数）';
COMMENT ON COLUMN mkt_deal_attribution.source_type IS 'LIVE_SESSION 直播场次 / SHORT_VIDEO 短视频 / POSTER 海报（词表预留，本期无写入方）';

-- ③ 回写游标（单行 chk id=1，镜像 auto_grant_state；NULL → 首轮 2000-01-01 全量回填）
CREATE TABLE IF NOT EXISTS deal_backfill_state (
    id               SMALLINT PRIMARY KEY,
    last_paid_at     TIMESTAMPTZ,
    last_refunded_at TIMESTAMPTZ,
    CONSTRAINT chk_deal_backfill_single CHECK (id = 1)
);
INSERT INTO deal_backfill_state (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE deal_backfill_state IS '成交回写游标（P5-B92，单行 id=1）：paid/refund 分段游标，段成功才推进，故障不推进下轮自愈';
