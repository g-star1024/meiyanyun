-- V94__outbox_sentinel.sql
-- 美研云门店中台 - F2 #21 outbox 哨兵缺口：outbox_record 补 error / retry_count 列
-- 创建时间: 2026-10-09
-- 数据库: PostgreSQL 15+
--
-- 版本号说明：全库共享 flyway_schema_history、版本号全局递增（非 finance 目录内连续）。
--   finance 目录内此前最高 V39，但真实库（prod/seed）已应用至 V93（含他服务迁移），
--   故本迁移取全局下一空闲号 V94，勿按目录内最大号文件臆断。
--
-- 背景：
--   outbox_record 自 B3 引入英文状态机（PENDING→RECONCILED/DIFF→ADJUSTED）后，
--   状态推进全靠人工对账端点触发，无任何自动机制：三方对账（reconcile/tripartite）
--   是只读聚合报告，只提示「请人工挂 DIFF」，不写 outbox；fin_setting.outbox_retry
--   配置（默认 3）有定义但无消费方。实证：批⑧归档 30 条 outbox 全 PENDING 永不推进。
--   本迁移为 @Scheduled 定时哨兵补齐落库列：error 记录哨兵扫描原因、retry_count
--   记录已重试次数（超 outbox_retry 上限自动挂 DIFF 进入人工调平）。
--
-- 幂等：ADD COLUMN IF NOT EXISTS，可重入。
-- 迁移后 finance 的 JPA ddl-auto=update 对 outbox_record 仍 no-op（列已齐全）。

ALTER TABLE outbox_record ADD COLUMN IF NOT EXISTS error TEXT;
ALTER TABLE outbox_record ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;
