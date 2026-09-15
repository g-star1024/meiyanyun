-- V32__marketing_schema_drift_fix.sql
-- 美研云门店中台 - 域⑤营销域：修复存量库 marketing_cfg 历史列 drift（P5-B52 卡1 连带）
-- 版本号说明：全库共享 flyway_schema_history 全局递增——V15-V29 由 ai-service 持有、
--   V30/V31 由 audit-service 持有，本服务（V11-V14）之后首个空号为 V32，故取 V32。
-- 创建时间: 2026-09-16
-- 数据库: PostgreSQL 15+
--
-- 背景（P5-B52 卡1 seed 栈启动排障实证）：
--   seed 库 marketing_cfg 是 JPA ddl-auto=update 时代建出的旧表；V11 采用
--   CREATE TABLE IF NOT EXISTS，表已存在时整句跳过、不会 ALTER 补列。B34 新增的
--   writeoff_fallback_store_code 仅落到全新库目标态与 JPA update 兜底路径上，
--   seed 旧表缺该列。B48 卡3 把 ddl-auto 从 update 收紧为 validate 后，
--   Hibernate 启动校验直接 fail-fast：
--     missing column [writeoff_fallback_store_code] in table [marketing_cfg]
--   经 information_schema 对全部 18 张营销域表逐列全量 diff，唯一 drift 即此列，
--   其余 17 表列结构与 V11-V14 目标态一致。
--
-- 幂等与安全：
--   ADD COLUMN IF NOT EXISTS（PG9.6+）——
--     · seed 库：缺列则补，VARCHAR(32) 可空、无 DEFAULT，存量行不回填，零数据风险；
--     · 正式库：B34 上线后已由 JPA update 补出同名列，整句跳过；
--     · 全新库：V11 已建出该列，整句跳过。
--   列定义与 V11 L47 / MarketingCfg.java @Column(length=32) 完全一致。

ALTER TABLE marketing_cfg
    ADD COLUMN IF NOT EXISTS writeoff_fallback_store_code VARCHAR(32);
