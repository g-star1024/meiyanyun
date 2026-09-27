-- ================================================================
-- V69 · T2 数据服务权限申请表（T2-B3 数据服务）
-- 铁律0：仅新增 data_service_permission；零物理外键（照 V63-V66 先例）
-- ================================================================

CREATE TABLE IF NOT EXISTS data_service_permission (
    id           BIGSERIAL    PRIMARY KEY,
    service_id   BIGINT       NOT NULL,
    service_name VARCHAR(100) NOT NULL,
    applicant    VARCHAR(50)  NOT NULL,
    reason       TEXT,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    applied_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at   TIMESTAMPTZ,
    decided_by   VARCHAR(50),
    CONSTRAINT chk_ds_perm_status CHECK (status IN ('PENDING','APPROVED','REJECTED'))
);

COMMENT ON TABLE  data_service_permission              IS 'T2 数据服务权限申请（申请→审批）';
COMMENT ON COLUMN data_service_permission.service_id   IS '数据服务 ID（逻辑引用 data_service.id）';
COMMENT ON COLUMN data_service_permission.service_name IS '服务名称（申请时冗余快照）';
COMMENT ON COLUMN data_service_permission.applicant    IS '申请人（当前登录用户）';
COMMENT ON COLUMN data_service_permission.reason       IS '申请理由';
COMMENT ON COLUMN data_service_permission.status       IS 'PENDING=待审批 / APPROVED=已通过 / REJECTED=已驳回';
COMMENT ON COLUMN data_service_permission.applied_at   IS '申请时间';
COMMENT ON COLUMN data_service_permission.decided_at   IS '审批时间';
COMMENT ON COLUMN data_service_permission.decided_by   IS '审批人';

CREATE INDEX IF NOT EXISTS idx_ds_perm_status     ON data_service_permission (status);
CREATE INDEX IF NOT EXISTS idx_ds_perm_service_id ON data_service_permission (service_id);
