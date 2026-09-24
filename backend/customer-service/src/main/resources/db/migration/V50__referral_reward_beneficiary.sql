-- ============================================================================
-- V50 · P5-B91 转介绍 v2：referral_reward 加受益人列
--
-- 版本号说明：flyway_schema_history 全库共享，版本号全局递增，本迁移取 V50。
-- 创建时间：2026-09-24
-- 数据库：PostgreSQL 15+
--
-- 背景（DESIGN-P5-B91 §2 D6）：
--   二级返佣受益人≠所属单推荐人（填上线推荐人），实体无受益人列 → 加列。
--   NULL=所属单推荐人（默认口径，与 v1 手动登记一致）。
--
-- 设计约束（对齐 V44/V47）：
--   1. 不建物理外键，跨表一律逻辑引用；
--   2. 幂等可重入：ADD COLUMN IF NOT EXISTS；
--   3. 迁移后 JPA ddl-auto=update 对本列 no-op；
--   4. 不加索引（查询面不走受益人过滤，统计走 referral_id/idem_key 既有索引）。
-- ============================================================================

ALTER TABLE referral_reward
    ADD COLUMN IF NOT EXISTS beneficiary_customer_id VARCHAR(24);

COMMENT ON COLUMN referral_reward.beneficiary_customer_id
    IS '受益人客户号（P5-B91）：NULL=所属单推荐人；二级返佣填上线推荐人';
