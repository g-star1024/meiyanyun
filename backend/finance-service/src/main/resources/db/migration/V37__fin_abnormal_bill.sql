-- V37__fin_abnormal_bill.sql
-- 美研云门店中台 - B63 卡1 L84 异常账务处置登记（长短款/错账登记＋审批闭环＋终审动账）
-- 创建时间: 2026-09-19
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   L84 前财务域只有只读仪表盘，长短款/错账无登记入口（FundEntryService L323
--   人工 MATERIAL/LOSS 分录直接 422 拒绝）。本迁移落 fin_abnormal_bill：
--     登记 PENDING_APPROVAL → 同步提 txn 审批（bizType=FIN_ADJUSTMENT）
--       → 终审回调 APPROVED → POST /{billNo}/dispose 入 ADJUST 资金分录 → DISPOSED
--       → 驳回回调 REJECTED（终态，不动账）
--   人工处置全程禁直接写 MATERIAL/LOSS 分录，动账只走终审后 ADJUST（红线）。
--
-- 幂等：CREATE TABLE/INDEX IF NOT EXISTS，双库（meiyun_core / meiyun_seed）各自
--   Flyway 独立应用一次，可重入；迁移后 JPA ddl-auto=update 对本表 no-op。

CREATE TABLE IF NOT EXISTS fin_abnormal_bill (
    bill_no                 VARCHAR(24)   PRIMARY KEY,          -- AB+yyyyMMdd+'-'+6位序号（北京日）
    idem_key                VARCHAR(64),                         -- 登记幂等键（前端连点/重试重传去重，可空；服务端登记亦可空）
    store_code              VARCHAR(16)   NOT NULL,             -- 门店码（数据域隔离）
    type                    VARCHAR(8)    NOT NULL,             -- SHORT 短款 / LONG 长款 / WRONG 错账
    direction               VARCHAR(4),                         -- WRONG 登记时必填 IN/OUT；SHORT/LONG 空（由类型推导）
    amount_fen              BIGINT        NOT NULL,             -- 金额（分，始终为正）
    source                  VARCHAR(8)    NOT NULL DEFAULT 'MANUAL',  -- MANUAL 手工登记 / RECONCILE 对账差异转入
    outbox_id               BIGINT,                             -- source=RECONCILE 时关联 outbox_record.id
    reason                  VARCHAR(256)  NOT NULL,             -- 事由（中文）
    status                  VARCHAR(16)   NOT NULL DEFAULT 'PENDING_APPROVAL',
    approval_no             VARCHAR(32),                        -- txn 审批中心单号
    dispose_fund_entry_id   BIGINT,                             -- 处置后资金分录 fund_entry.entry_id
    created_by              VARCHAR(16)   NOT NULL,             -- 登记人员工号
    reviewer                VARCHAR(16),                        -- 终审人员工号（回调落）
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    approved_at             TIMESTAMPTZ,                        -- 终审（通过/驳回）时间
    disposed_at             TIMESTAMPTZ,                        -- 处置动账完成时间
    CONSTRAINT chk_fin_abnormal_bill_type CHECK (type IN ('SHORT','LONG','WRONG')),
    CONSTRAINT chk_fin_abnormal_bill_direction CHECK (direction IS NULL OR direction IN ('IN','OUT')),
    CONSTRAINT chk_fin_abnormal_bill_amount CHECK (amount_fen > 0),
    CONSTRAINT chk_fin_abnormal_bill_source CHECK (source IN ('MANUAL','RECONCILE')),
    CONSTRAINT chk_fin_abnormal_bill_status CHECK (status IN ('PENDING_APPROVAL','APPROVED','REJECTED','DISPOSED'))
);
CREATE INDEX IF NOT EXISTS idx_fin_abnormal_bill_store ON fin_abnormal_bill(store_code);
CREATE INDEX IF NOT EXISTS idx_fin_abnormal_bill_status ON fin_abnormal_bill(status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_fin_abnormal_bill_approval ON fin_abnormal_bill(approval_no);
CREATE UNIQUE INDEX IF NOT EXISTS idx_fin_abnormal_bill_idem ON fin_abnormal_bill(idem_key);
CREATE INDEX IF NOT EXISTS idx_fin_abnormal_bill_outbox ON fin_abnormal_bill(outbox_id);
