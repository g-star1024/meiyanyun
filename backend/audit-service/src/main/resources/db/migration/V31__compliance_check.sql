-- V31__compliance_check.sql
-- 美研云门店中台 - audit-service 合规中心（B49 卡9，DELIVERY-P5-B49 §卡9）：compliance_check 建表
-- 版本号说明：全库共享 flyway_schema_history 全局递增版本号——
--   V1-V30 已全占（customer V1/V2/V3/V5/V9、finance V4/V6/V7/V8、audit V10/V30、
--   marketing V11-V14、ai V15-V29），本迁移取下一空号 V31。
-- 创建时间: 2026-09-15
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   M1 集团屏合规中心 /m1-compliance 去 mock：六类检查项（QUALIFICATION 资质证照/
--   CONSENT 知情同意/DRUG_TRACE 药品溯源/PRIVACY 隐私合规/AD 医疗广告/INFECTION 院感管理）
--   × 四态（PASS 合规/WARN 预警/FAIL 不合规/PENDING 待检），按门店维度产生检查记录；
--   FAIL 项必须整改并复检。审计留痕复用 audit_log（bizType=COMPLIANCE，
--   ip/risk/target 落 payload jsonb，不扩 audit_log 列）。
--
-- 幂等与时序：
--   CREATE TABLE IF NOT EXISTS——全新库（Flyway 先于 JPA 执行）直接建出全量结构；
--   ddl-auto=update 先建表的场景整句跳过，CHECK 约束由下方 DO 块按 pg_constraint 补齐，
--   两种场景均可重入。

CREATE TABLE IF NOT EXISTS compliance_check (
    id            BIGSERIAL PRIMARY KEY,
    category      VARCHAR(16)  NOT NULL,
    title         VARCHAR(128) NOT NULL,
    requirement   VARCHAR(256) NOT NULL,
    store_name    VARCHAR(64)  NOT NULL,
    status        VARCHAR(8)   NOT NULL,
    last_check_at TIMESTAMPTZ  NOT NULL,
    checker       VARCHAR(32)  NOT NULL,
    evidence      VARCHAR(256),
    due_date      DATE,
    remark        VARCHAR(512),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'compliance_check_category_chk') THEN
        ALTER TABLE compliance_check ADD CONSTRAINT compliance_check_category_chk
            CHECK (category IN ('QUALIFICATION','CONSENT','DRUG_TRACE','PRIVACY','AD','INFECTION'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'compliance_check_status_chk') THEN
        ALTER TABLE compliance_check ADD CONSTRAINT compliance_check_status_chk
            CHECK (status IN ('PASS','WARN','FAIL','PENDING'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_compliance_check_category ON compliance_check (category);
CREATE INDEX IF NOT EXISTS idx_compliance_check_status ON compliance_check (status);
