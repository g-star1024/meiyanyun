-- ============================================================
-- V61 客诉表（M3-B8 / DESIGN-M3 §3 M3-20：投诉与医疗风险处理）
-- 单表：complaint（状态机五态：待受理→处理中→待结案审批→已结案/已驳回）
-- 号段：flyway_schema_history 全库共享，全局最大 V60（B6 risk_rule）后取 V61/V62。
-- 幂等可重入：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS。
-- 不建物理外键：customer_id / store_code 为逻辑引用（见列 COMMENT）。
-- 金额口径：DB 存分（BIGINT，照 V44 deal_amount_cents 先例），API 层元↔分换算；
--   签署层级 sign_tier 服务端统算（>=20000 元→L3 / >=5000 元→L2 / 否则 L1，
--   与前端 config/settings.ts DEFAULT dualSign 同源）。
-- ddl-auto=validate 硬约束（B48 卡3 收口）：本 DDL 与 Complaint 实体映射逐列对齐。
-- ============================================================

CREATE TABLE IF NOT EXISTS complaint (
    id                        BIGSERIAL    PRIMARY KEY,
    complaint_no              VARCHAR(32)  NOT NULL UNIQUE,
    customer_id               VARCHAR(32),
    customer_name             VARCHAR(64)  NOT NULL,
    source                    VARCHAR(16)  NOT NULL,
    severity                  VARCHAR(8)   NOT NULL,
    category                  VARCHAR(16)  NOT NULL,
    medical_risk              BOOLEAN      NOT NULL DEFAULT FALSE,
    description               TEXT         NOT NULL,
    related_order_no          VARCHAR(32),
    store_code                VARCHAR(16),
    store_name                VARCHAR(64),
    status                    VARCHAR(16)  NOT NULL DEFAULT 'PENDING_ACCEPT',
    compensation_amount_cents BIGINT       NOT NULL DEFAULT 0,
    sign_tier                 VARCHAR(4)   NOT NULL DEFAULT 'L1',
    resolution                TEXT,
    accepted_by_name          VARCHAR(64),
    accepted_at               TIMESTAMPTZ,
    submitted_by_name         VARCHAR(64),
    submitted_at              TIMESTAMPTZ,
    closed_by_name            VARCHAR(64),
    closed_at                 TIMESTAMPTZ,
    rejection_reason          TEXT,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_complaint_source    CHECK (source IN ('STORE', 'PHONE', 'ONLINE', 'THIRD_PARTY')),
    CONSTRAINT chk_complaint_severity  CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT chk_complaint_category  CHECK (category IN ('SERVICE', 'MEDICAL', 'BILLING', 'OUTCOME', 'OTHER')),
    CONSTRAINT chk_complaint_status    CHECK (status IN ('PENDING_ACCEPT', 'PROCESSING', 'PENDING_REVIEW', 'CLOSED', 'REJECTED')),
    CONSTRAINT chk_complaint_sign_tier CHECK (sign_tier IN ('L1', 'L2', 'L3'))
);

COMMENT ON TABLE  complaint IS '客诉单（M3-B8 / DESIGN-M3 §3 M3-20）：投诉登记→受理→处理方案→结案审批五态状态机；medical_risk=true 医疗风险单列表高亮并强制留痕处理方案；签署层级服务端统算';
COMMENT ON COLUMN complaint.complaint_no IS '客诉单号：TS+yyyyMMdd-3位当日序号，DB 当日最大号+1（禁内存序列，照 RiskService.nextRiskNo 口径）';
COMMENT ON COLUMN complaint.customer_id IS '客户逻辑引用（无物理外键；演示种子为 C-4xx 字面值）';
COMMENT ON COLUMN complaint.customer_name IS '客户姓名（登记时刻快照）';
COMMENT ON COLUMN complaint.source IS '投诉来源四值：STORE 门店 / PHONE 电话 / ONLINE 线上 / THIRD_PARTY 第三方平台';
COMMENT ON COLUMN complaint.severity IS '严重度三值：LOW 低 / MEDIUM 中 / HIGH 高';
COMMENT ON COLUMN complaint.category IS '投诉分类五值：SERVICE 服务 / MEDICAL 医疗 / BILLING 账单 / OUTCOME 效果 / OTHER 其他';
COMMENT ON COLUMN complaint.medical_risk IS '医疗风险标记：true=列表高亮并强制留痕处理方案';
COMMENT ON COLUMN complaint.description IS '投诉描述（必填）';
COMMENT ON COLUMN complaint.related_order_no IS '关联订单号（选填）';
COMMENT ON COLUMN complaint.store_code IS '门店编码：登记人门店（DataScope.current().storeCode()；NULL=全连锁身份登记）';
COMMENT ON COLUMN complaint.store_name IS '门店名称（登记时刻快照）';
COMMENT ON COLUMN complaint.status IS '状态机五态：PENDING_ACCEPT 待受理 / PROCESSING 处理中 / PENDING_REVIEW 待结案审批 / CLOSED 已结案 / REJECTED 已驳回';
COMMENT ON COLUMN complaint.compensation_amount_cents IS '赔付金额（分）：DB 存分，API 层元↔分换算（V44 先例）';
COMMENT ON COLUMN complaint.sign_tier IS '签署层级：>=20000 元→L3 / >=5000 元→L2 / 否则 L1（服务端统算，赔付变更时重算）';
COMMENT ON COLUMN complaint.resolution IS '处理方案（提交结案审批时必填）';
COMMENT ON COLUMN complaint.accepted_by_name IS '受理人（DataScope.currentActor()）';
COMMENT ON COLUMN complaint.accepted_at IS '受理时刻';
COMMENT ON COLUMN complaint.submitted_by_name IS '处理方案提交人';
COMMENT ON COLUMN complaint.submitted_at IS '处理方案提交时刻';
COMMENT ON COLUMN complaint.closed_by_name IS '结案/驳回操作人';
COMMENT ON COLUMN complaint.closed_at IS '结案/驳回时刻';
COMMENT ON COLUMN complaint.rejection_reason IS '驳回原因（驳回必填）';
COMMENT ON COLUMN complaint.created_at IS '登记时刻（列表排序列）';
COMMENT ON COLUMN complaint.updated_at IS '最近更新时刻（状态流转刷 now()）';

CREATE INDEX IF NOT EXISTS idx_complaint_status       ON complaint (status);
CREATE INDEX IF NOT EXISTS idx_complaint_store_code    ON complaint (store_code);
CREATE INDEX IF NOT EXISTS idx_complaint_customer_id   ON complaint (customer_id);
CREATE INDEX IF NOT EXISTS idx_complaint_medical_risk  ON complaint (medical_risk) WHERE medical_risk;
