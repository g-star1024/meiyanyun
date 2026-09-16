-- V33__report_job_verify.sql
-- 美研云门店中台 - P5-B56 报告哈希验真 UI（04-backlog L123 第①类）
-- 创建时间: 2026-09-16
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   B49 卡11 report_job.content（BYTEA）已持久化含 UTF-8 BOM 的 CSV 最终字节，
--   但下载文件名在下载时刻用 LocalDateTime.now() 现拼（ReportService.download），
--   同任务多次下载文件名不同；且内容没有落库指纹，无法在 UI 侧做哈希验真。
--   P5-B56 第①类：①文件名冻结为生成时刻（锚 job.created_at）落库，下载直接读列；
--   ②content_hash = SHA-256(content 原始字节，含 BOM) 的 64 位小写 hex，
--     生成成功时落库；验真端点按 jobId 重算常量时间比对。
--
-- 历史种子行（content IS NULL）两列均留 NULL，不回填、不伪造
-- （验真返回 ok=false / reason=HISTORICAL_NOT_RETAINED）。
--
-- 幂等：ADD COLUMN IF NOT EXISTS 可重入。

ALTER TABLE report_job ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE report_job ADD COLUMN IF NOT EXISTS file_name VARCHAR(128);
