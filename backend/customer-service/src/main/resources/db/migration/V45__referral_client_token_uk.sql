-- ============================================================================
-- V45 · P5-B86 转介绍：client_token 幂等唯一约束（OCR 卡1 复审补项）
--
-- 版本号说明：flyway_schema_history 全库共享，版本号全局递增，本迁移取 V45。
-- 创建时间：2026-09-23
-- 数据库：PostgreSQL 15+
--
-- 背景：V44 的 client_token 仅靠 findFirstByClientToken check-then-act，
--   并发重放同 token 且无进行态同 referee 绑定时可重复落库。
--   本迁移补部分唯一索引兜底（NULL 放行：历史无 token 单不受影响）。
-- 幂等可重入：CREATE UNIQUE INDEX IF NOT EXISTS。
-- ============================================================================
CREATE UNIQUE INDEX IF NOT EXISTS uk_referral_client_token
    ON referral(client_token)
    WHERE client_token IS NOT NULL;
