-- V10__audit_log_baseline.sql
-- 美研云门店中台 - audit-service 接入 Flyway：audit_log 历史手动 ALTER 补登
-- 版本号说明：全库共享 flyway_schema_history 全局递增版本号——
--   V1/V2/V3/V5/V9 由 customer-service 持有，V4/V6/V7/V8 由 finance-service 持有
--   （运行态核实 flyway_schema_history V0(baseline)~V9 均 success），故本迁移取首个空号 V10。
-- 创建时间: 2026-09-12
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   audit-service 此前无 Flyway，audit_log 由 JPA ddl-auto=update 建表，两次列宽变更靠手动 ALTER：
--     1) biz_type VARCHAR(16) → VARCHAR(32)：B32 事件类型扩展后 16 长度不足，线上手动 ALTER；
--     2) txn_no   VARCHAR(24) → VARCHAR(128)：B37 消费自动发赠金幂等键 RULE:{ruleId}:{orderNo}
--        可能超长，卡2 附修手动 ALTER。
--   现网两列物理上已是目标宽度（迁移执行时为 no-op），本脚本把这两次变更补登为版本化迁移，
--   同时校准仓库根 db/schema.sql 基线 biz_type 仍为 VARCHAR(16) 的历史漂移。
--
-- 幂等与时序：
--   ALTER TABLE IF EXISTS + ALTER COLUMN TYPE——
--     · 现网库：表存在且列已为目标宽度，PostgreSQL 重设同宽类型为 no-op，不扫描不报错；
--     · 全新库：Flyway 先于 JPA 执行，audit_log 尚未建（IF EXISTS 跳过），
--       随后 Hibernate ddl-auto=update 按实体（length=32/128）直接建出目标宽度。
--   两种场景均可重入。

ALTER TABLE IF EXISTS audit_log
    ALTER COLUMN biz_type TYPE VARCHAR(32);

ALTER TABLE IF EXISTS audit_log
    ALTER COLUMN txn_no TYPE VARCHAR(128);
