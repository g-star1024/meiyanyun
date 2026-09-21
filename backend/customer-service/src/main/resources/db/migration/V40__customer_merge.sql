-- V40__customer_merge.sql
-- 美研云门店中台 - 域①客户域 P5-B84 撞单合并写期（合并单据 + 迁移快照 + customer 扩 merged_into/merged_at）
-- 版本号说明：全库共享 flyway_schema_history 全局递增版本号——V37 fin abnormal bill、V38 prepay monitor、
--   V39 fin input invoice 均已占用（meiyun_core/meiyun_seed 运行态核实），故本迁移取首个空号 V40。
-- 创建时间: 2026-09-21
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   P5-B84 卡1 撞单合并写期——
--   1) customer_merge 合并单据：状态机 PROPOSED/REVIEWING/APPROVED/MERGED/REJECTED/NOT_DUPLICATE，
--      customer:merge 持有者直接执行（PROPOSED→APPROVED→MERGED 同事务链，审批人=执行人）；
--      idem_key 唯一列承载前端 clientToken / 服务端兜底键（pairId:masterId、ND:a:b），幂等重放返回原单；
--   2) customer_merge_snapshot 迁移留痕：每迁移一张子表落一行（row_ids jsonb 记迁出主键数组），
--      table_name='customer' 行存 loser 合并前全字段快照（loser_before jsonb，本批仅留痕，
--      回滚端点留 Backlog）；
--   3) customer 扩 merged_into/merged_at：loser 处置仅置这两列（status 不动，D1），
--      全站列表/搜索/点选/查重默认 merged_into IS NULL 过滤，id 直连详情留痕可访问。
--
-- 设计约束：
--   - 两表均不建物理外键（对齐 points_ledger/card_ledger append-only 哲学，兼容 DSAR 匿名化）；
--   - 22 张迁移子表不扩列不动结构，迁移 = UPDATE customer_id，快照行数 = 迁移行数 + 1（customer 行）；
--   - 不迁 6 张：customer_monthly_spend/ai_customer_profile/ai_churn_prediction/
--     ai_repurchase_prediction（统计 AI 快照重算覆盖）+ dsar_request/customer_search_event（合规留痕）；
--   - 幂等：CREATE TABLE/INDEX/ADD COLUMN 全部 IF NOT EXISTS，可重入；迁移后 ddl-auto=update 对新表全部 no-op（列已齐全）。

-- ============================================================
-- 1. customer 扩 loser 处置列（D1：status 不动，仅置 merged_into/merged_at）
-- ============================================================
ALTER TABLE customer ADD COLUMN IF NOT EXISTS merged_into VARCHAR(16);   -- 合并去向 master 客户号；NULL=活跃档案
ALTER TABLE customer ADD COLUMN IF NOT EXISTS merged_at TIMESTAMPTZ;     -- 合并执行时间
CREATE INDEX IF NOT EXISTS idx_customer_merged_into ON customer(merged_into);

-- ============================================================
-- 2. customer_merge 合并单据（状态机 + 幂等键 + 双人工号留痕）
-- ============================================================
CREATE TABLE IF NOT EXISTS customer_merge (
    merge_id       VARCHAR(20)  PRIMARY KEY,              -- MG + 8位日期 + '-' + 6位序号（当日 max+1）
    master_id      VARCHAR(16)  NOT NULL,                 -- 胜方（Survivorship：创建时间最早，可手动换边）
    merged_id      VARCHAR(16)  NOT NULL,                 -- 被合并方 loser（单笔一个 merged，多单据多行）
    group_type     VARCHAR(16)  NOT NULL,                 -- POOL / SAME_STORE / CROSS_STORE（快照自候选对）
    match_reasons  VARCHAR(64),                           -- 命中理由 CSV：PHONE / NAME_BIRTHDAY / DEVICE / IDCARD
    score          NUMERIC(3,2),                          -- 快照 0.95
    reason         VARCHAR(512),                          -- 人工填写的合并事由
    status         VARCHAR(16)  NOT NULL,                 -- PROPOSED/REVIEWING/APPROVED/MERGED/REJECTED/NOT_DUPLICATE
    idem_key       VARCHAR(64),                           -- 幂等键：前端 clientToken 或服务端兜底键
    requested_by   VARCHAR(16)  NOT NULL,                 -- 发起人工号
    approved_by    VARCHAR(16),                           -- 审批人工号（直接执行时=requested_by）
    approved_at    TIMESTAMPTZ,
    executed_at    TIMESTAMPTZ,                           -- 迁移执行完成时间
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_merge_idem UNIQUE (idem_key),
    CONSTRAINT chk_customer_merge_status CHECK (
        status IN ('PROPOSED','REVIEWING','APPROVED','MERGED','REJECTED','NOT_DUPLICATE')),
    CONSTRAINT chk_customer_merge_group_type CHECK (
        group_type IN ('POOL','SAME_STORE','CROSS_STORE'))
);
CREATE INDEX IF NOT EXISTS idx_customer_merge_master ON customer_merge(master_id);
CREATE INDEX IF NOT EXISTS idx_customer_merge_merged ON customer_merge(merged_id);
CREATE INDEX IF NOT EXISTS idx_customer_merge_status ON customer_merge(status);

-- ============================================================
-- 3. customer_merge_snapshot 迁移留痕（每子表一行 + customer 行存 loser 全字段快照）
-- ============================================================
CREATE TABLE IF NOT EXISTS customer_merge_snapshot (
    id            BIGSERIAL    PRIMARY KEY,
    merge_id      VARCHAR(20)  NOT NULL,                  -- 关联 customer_merge（逻辑引用不建物理 FK）
    table_name    VARCHAR(32)  NOT NULL,                  -- 被迁移子表名；table_name='customer' 行为 loser 全字段快照
    row_ids       JSONB        NOT NULL,                  -- 本次从 merged_id 迁出的主键/行标识数组（JSON）
    moved_count   INTEGER      NOT NULL,
    loser_before  JSONB                                 -- 仅 table_name='customer' 行有值：loser 合并前全字段快照
);
CREATE INDEX IF NOT EXISTS idx_customer_merge_snapshot_merge ON customer_merge_snapshot(merge_id);
