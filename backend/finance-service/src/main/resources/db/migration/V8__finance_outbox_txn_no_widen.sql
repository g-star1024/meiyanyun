-- V8__finance_outbox_txn_no_widen.sql
-- 美研云门店中台 - B11 月结成本结转：outbox_record.txn_no 扩列宽
-- 创建时间: 2026-09-06
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   FundEntryService.postOne 落每笔资金分录时同写 outbox_record 对账台账，
--   其中 txn_no = 分录 bizRef（业务单据号）。B11 之前各域 bizRef 均为短号（退款/退款卡
--   原始流水号，≤24 位）；B11 成本结转的 bizRef 为复合格式
--   「规则号:yyyy-MM:门店码」（如 CCR-SEED-001:2026-09:SST01），
--   种子规则 26 位、用户自建规则（CCRyyyyMMdd-序号，门店码最长 16 位）最长约 40 位，
--   超出原 VARCHAR(24) → JPA save 触发 value too long，整笔结转事务回滚（run 500）。
--
--   扩到 VARCHAR(64)：与 fund_entry.idem_key 同宽，覆盖
--   「CARRY:」7 + 规则 12 + 「:」1 + 月份 7 + 「:」1 + 门店 16 = 44 位的幂等键留有余量；
--   纯扩列宽，不涉及数据搬迁/状态机/CHECK 变更，向后兼容。
--
-- 幂等：ALTER TABLE ALTER COLUMN TYPE 本身可重入（重复执行结果不变）。

ALTER TABLE outbox_record ALTER COLUMN txn_no TYPE VARCHAR(64);
