-- ============================================================
-- V54 NPS 回访记录表（M3-B1 / DESIGN-M3 §3 M3-12）
-- 单表：nps_record（NPS 问卷回执＋跟进状态）
-- 说明：flyway_schema_history 全库共享，版本号全局递增（全局最大 V53 后取 V54）。
-- 幂等可重入：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS。
-- 不建物理外键：customer_id 为逻辑引用（可空，匿名/未匹配照 V52 customer_id 先例），
--   customer_name 冗余快照保证未匹配时仍可展示。
-- 提交幂等：(customer_id, period) 部分唯一索引（DESIGN L124），
--   同一客户同一周期重复提交撞唯一约束 → 服务层捕 DataIntegrityViolationException 返 dedup=true。
-- 周期口径：period 存 ISO 周 'YYYY-Wnn'（如 '2026-W31'）；趋势按 date_trunc('week') 近 6 周聚合，
--   前端适配层转 'Wnn' 短标签（M3-12 近 6 周趋势柱）。
-- 分类词表：category PROMOTER(≥9)/PASSIVE(≥7)/DETRACTOR(≤6)，服务层按 score 推导落库（不以前端入参为准）。
-- ddl-auto=update 双轨（application.yml）：Hibernate 依实体映射补列，本 DDL 为准绳。
-- ============================================================

CREATE TABLE IF NOT EXISTS nps_record (
    id            BIGSERIAL   PRIMARY KEY,
    record_no     VARCHAR(24) NOT NULL,
    customer_id   VARCHAR(16),
    customer_name VARCHAR(64) NOT NULL DEFAULT '',
    score         SMALLINT    NOT NULL,
    category      VARCHAR(16) NOT NULL,
    service       VARCHAR(128) NOT NULL DEFAULT '',
    tags          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    comment       TEXT        NOT NULL DEFAULT '',
    period        VARCHAR(16) NOT NULL,
    follow_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    follow_note   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_nps_record_score CHECK (score BETWEEN 0 AND 10),
    CONSTRAINT chk_nps_record_category CHECK (category IN ('PROMOTER', 'PASSIVE', 'DETRACTOR')),
    CONSTRAINT chk_nps_record_follow CHECK (follow_status IN ('PENDING', 'FOLLOWED'))
);

COMMENT ON TABLE  nps_record IS 'NPS 回访记录（M3-B1 / DESIGN-M3 §3 M3-12）：问卷回执＋跟进状态；提交幂等 (customer_id,period)；趋势按周聚合近 6 周供 M3-12 趋势柱/环形图/分布条';
COMMENT ON COLUMN nps_record.record_no IS '记录单号：NR 前缀序号递增（照 TA%03d 先例，NR%04d）';
COMMENT ON COLUMN nps_record.customer_id IS '客户号（逻辑引用 customer.customer_id 如 M001，可空）：匿名/未匹配为 NULL；提交时按姓名匹配 customer 表富化';
COMMENT ON COLUMN nps_record.customer_name IS '客户姓名冗余快照：未匹配 customer 时仍可展示（M3-12 列表行/详情卡）';
COMMENT ON COLUMN nps_record.score IS 'NPS 打分 0-10（CHECK 0..10）';
COMMENT ON COLUMN nps_record.category IS '分类三值：PROMOTER 推荐者(≥9)/PASSIVE 被动者(≥7)/DETRACTOR 贬损者(≤6)，服务层按 score 推导';
COMMENT ON COLUMN nps_record.service IS '回访项目（如 热玛吉紧致/水光补水）';
COMMENT ON COLUMN nps_record.tags IS '评价标签 JSON 数组（如 ["态度好","效果明显"]），M3-12 列表行展示前 3';
COMMENT ON COLUMN nps_record.comment IS '客户评语文本';
COMMENT ON COLUMN nps_record.period IS '提交周期 ISO 周 ''YYYY-Wnn''：与 customer_id 组提交幂等；趋势桶按周聚合';
COMMENT ON COLUMN nps_record.follow_status IS '跟进状态：PENDING 待跟进 / FOLLOWED 已跟进（M3-12 tabs 第五键＋CStatusPill）';
COMMENT ON COLUMN nps_record.follow_note IS '跟进备注（标记已跟进时填，如「已电话回访，客户接受重做安排」）';
COMMENT ON COLUMN nps_record.created_at IS '记录创建时刻（TIMESTAMPTZ）';
COMMENT ON COLUMN nps_record.updated_at IS '最近更新时刻（跟进状态/备注变更时刷新）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_nps_record_record_no ON nps_record (record_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_nps_record_customer_period
    ON nps_record (customer_id, period) WHERE customer_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_nps_record_period  ON nps_record (period);
CREATE INDEX IF NOT EXISTS idx_nps_record_created ON nps_record (created_at DESC);
