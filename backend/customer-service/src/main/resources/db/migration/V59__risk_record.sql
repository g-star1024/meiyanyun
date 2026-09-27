-- ============================================================
-- V59 风控记录表（M3-B6 / DESIGN-M3 §3 M3-17：黑名单与风控切真）
-- 单表：risk_record（黑/风险名单记录，列表/详情/KPI 直读本表）
-- 说明：flyway_schema_history 全库共享，版本号全局递增（全局最大 V58 后取 V59；
--   号段定案 2026-09-27 B5 开工拍压缩后续顺移一位，照 V58 头注）。
-- 幂等可重入：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS。
-- 不建物理外键：customer_id / store_code 为逻辑引用（见列 COMMENT）。
-- 状态机（照前端 mock 活规格，迁移前置校验在服务层，中文 4xx）：
--   提交拉黑→PENDING_REVIEW；审核通过→BLACKLISTED；审核驳回→WATCHING；
--   解除风险 BLACKLISTED|WATCHING→RELEASED。
-- ddl-auto=validate 硬约束（B48 卡3 收口）：本 DDL 与 RiskRecord 实体映射逐列对齐。
-- ============================================================

CREATE TABLE IF NOT EXISTS risk_record (
    id                 BIGSERIAL    PRIMARY KEY,
    risk_no            VARCHAR(32)  NOT NULL UNIQUE,
    customer_id        VARCHAR(32),
    customer_name      VARCHAR(64)  NOT NULL,
    phone_mask         VARCHAR(16)  NOT NULL DEFAULT '',
    level              VARCHAR(8)   NOT NULL,
    reason             VARCHAR(32)  NOT NULL,
    reason_detail      TEXT         NOT NULL DEFAULT '',
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING_REVIEW',
    hit_count          INTEGER      NOT NULL DEFAULT 1,
    block_transactions BOOLEAN      NOT NULL DEFAULT FALSE,
    operator           VARCHAR(64)  NOT NULL DEFAULT '',
    resolved_at        TIMESTAMPTZ,
    resolved_by        VARCHAR(64),
    timeline           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    store_code         VARCHAR(16),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_risk_record_level  CHECK (level IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT chk_risk_record_reason CHECK (reason IN ('FRAUD', 'CHARGEBACK', 'MALICIOUS_COMPLAINT', 'ILLEGAL_PRACTICE', 'OTHER')),
    CONSTRAINT chk_risk_record_status CHECK (status IN ('BLACKLISTED', 'WATCHING', 'RELEASED', 'PENDING_REVIEW'))
);

COMMENT ON TABLE  risk_record IS '风控记录（M3-B6 / DESIGN-M3 §3 M3-17）：黑/风险名单＋拉黑解黑审批状态机；风控页列表/详情/四 KPI 直读本表';
COMMENT ON COLUMN risk_record.risk_no IS '风控单号：RK+yyyyMMdd-6位当日序号，DB 当日最大号+1（禁内存序列，照 BizNoGenerator 口径）；种子保留 mock 字面值（如 RK20260820001）';
COMMENT ON COLUMN risk_record.customer_id IS '客户逻辑引用（无物理外键）：真实提交关联 customer.id 字符串；演示种子为 C-3xx 字面值，不关联真实行';
COMMENT ON COLUMN risk_record.customer_name IS '客户姓名（风控场景允许直存，D7 不脱敏）';
COMMENT ON COLUMN risk_record.phone_mask IS '手机号脱敏串（列表层即脱敏态，如 138****0044，照 referral.ts 脱敏口径直存）';
COMMENT ON COLUMN risk_record.level IS '风险级别三值：HIGH 高风险 / MEDIUM 中风险 / LOW 低风险';
COMMENT ON COLUMN risk_record.reason IS '风控原因五值：FRAUD 疑似欺诈 / CHARGEBACK 恶意退单 / MALICIOUS_COMPLAINT 恶意投诉 / ILLEGAL_PRACTICE 违规医托 / OTHER 其他';
COMMENT ON COLUMN risk_record.reason_detail IS '原因明细（人类可读，D7 风控原因直存不脱敏）';
COMMENT ON COLUMN risk_record.status IS '状态机四态：PENDING_REVIEW 待审核（提交拉黑初态）/ BLACKLISTED 已拉黑（审核通过）/ WATCHING 观察中（审核驳回）/ RELEASED 已解除（终态）';
COMMENT ON COLUMN risk_record.hit_count IS '规则累计命中次数：提交拉黑初值 1；列表按本列降序（照 mock 活规格）';
COMMENT ON COLUMN risk_record.block_transactions IS '是否拦截交易：提交拉黑=（level==HIGH）；审核通过强制 true；驳回/解除强制 false；真实交易拦截链在 txn 域（DESIGN §7 后续批）';
COMMENT ON COLUMN risk_record.operator IS '提交操作人（DataScope.currentActor()；系统自动命中时为「系统自动」）';
COMMENT ON COLUMN risk_record.resolved_at IS '处置时刻：审核通过/解除风险时填；待审核/观察中为 NULL';
COMMENT ON COLUMN risk_record.resolved_by IS '处置人（审核通过/解除风险时的 DataScope.currentActor()）';
COMMENT ON COLUMN risk_record.timeline IS '时间线 JSONB 数组：[{action,by,at,comment?}]，提交/审核/驳回/解除逐步追加（照 mock 活规格逐字动作文案）';
COMMENT ON COLUMN risk_record.store_code IS '门店编码：NULL=全连锁，填值=操作人门店（铁律-1-D；本列留痕操作域）';
COMMENT ON COLUMN risk_record.created_at IS '创建时刻（列表/详情展示列）';
COMMENT ON COLUMN risk_record.updated_at IS '最近更新时刻（状态流转刷 now()）';

CREATE INDEX IF NOT EXISTS idx_risk_record_status_level ON risk_record (status, level);
CREATE INDEX IF NOT EXISTS idx_risk_record_hit_count    ON risk_record (hit_count DESC);
CREATE INDEX IF NOT EXISTS idx_risk_record_created_at   ON risk_record (created_at DESC);
