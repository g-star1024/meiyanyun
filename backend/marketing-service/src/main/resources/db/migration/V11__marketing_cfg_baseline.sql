-- V11__marketing_cfg_baseline.sql
-- 美研云门店中台 - 域⑤营销域：marketing-service 接入 Flyway，首批纳管 marketing_cfg + auto_grant_state
-- 版本号说明：全库共享 flyway_schema_history 全局递增版本号——
--   V1/V2/V3/V5/V9 由 customer-service 持有，V4/V6/V7/V8 由 finance-service 持有，
--   V10 由 audit-service 持有（audit_log 列宽补登）；故本迁移取首个空号 V11。
-- 创建时间: 2026-09-12
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   marketing-service 此前无 Flyway，18 张域表全部由 JPA ddl-auto=update 建表/补列，
--   仓库根 db/schema.sql 基线严重漂移（marketing_cfg 仅 4 列且无主键，auto_grant_state 缺失）。
--   本批先纳管两张服务级单行表：
--     1) marketing_cfg  营销全局配置（单行 cfg_id=1）：老带新奖励/提成/周触达上限 +
--        M5-15 免打扰/审批/默认渠道（9 列）+ B34 券核销兜底门店，共 14 列；
--     2) auto_grant_state 消费满额自动发赠金游标（B37 卡2，单行 state_id=1）。
--   其余 16 张域表（券/活动/海报/直播等，部分历史脚本挂在 customer 的 V3）维持 JPA update，
--   后续按域分批纳管，不在本迁移范围。
--
-- 幂等与时序：
--   CREATE TABLE IF NOT EXISTS——
--     · 现网库：两表均已由 JPA 建出（marketing_cfg 14 列、auto_grant_state 5 列），整句跳过；
--     · 全新库：Flyway 先于 JPA 执行，按现网物理结构（pg_dump 核实）建出全列目标态，
--       随后 Hibernate ddl-auto=update 对两表 no-op。两种场景均可重入。
--   注意：仅 CREATE TABLE IF NOT EXISTS 不做 ALTER，存量库若缺新增列仍由 Hibernate update 补齐
--   （与 V9 customer_automation 同一范式），避免对现网做任何有数据风险的变更。

-- ============================================================
-- 1. marketing_cfg 营销全局配置（单行 cfg_id=1）
-- ============================================================
CREATE TABLE IF NOT EXISTS marketing_cfg (
    cfg_id                        INTEGER       PRIMARY KEY DEFAULT 1,
    referral_arrived_reward       INTEGER       NOT NULL DEFAULT 200,
    referral_deal_reward          INTEGER       NOT NULL DEFAULT 350,
    commission_rate               NUMERIC(4,2)  NOT NULL DEFAULT 0.05,
    weekly_push_limit             INTEGER       NOT NULL DEFAULT 3,
    -- M5-15 营销设置：免打扰 / 审批流 / 默认渠道（可空，空值按默认口径回落）
    quiet_hours_enabled           BOOLEAN,
    quiet_start                   VARCHAR(5),
    quiet_end                     VARCHAR(5),
    holiday_exempt                BOOLEAN,
    large_coupon_threshold_fen    BIGINT,
    push_requires_approval        BOOLEAN,
    approval_level                INTEGER,
    default_push_channels         VARCHAR(128),
    default_ad_channels           VARCHAR(256),
    -- B34 券核销兜底门店编码（集团/大区账号核销时的流水归属门店）
    writeoff_fallback_store_code  VARCHAR(32)
);

-- ============================================================
-- 2. auto_grant_state 消费满额自动发赠金游标（B37 卡2，单行 state_id=1）
-- ============================================================
CREATE TABLE IF NOT EXISTS auto_grant_state (
    state_id      INTEGER     PRIMARY KEY,
    last_granted  BIGINT,
    last_run_at   TIMESTAMPTZ,
    last_scanned  BIGINT,
    updated_at    TIMESTAMPTZ NOT NULL
);
