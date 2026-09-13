-- =============================================================================
-- V29__ai_privacy.sql
-- B47 卡8：A1 隐私合规（/ai/privacy）真实持久化——脱敏规则配置、等保三级达标台账、
--          合规报告导出（报告哈希真实覆盖区间内 append-only 审计链）
--
-- 版本链：全库共享 flyway_schema_history，V15~V28 为 ai-service，本脚本占用 V29。
-- DDL 全部 IF NOT EXISTS，可在历史 ddl-auto=update 环境重入。
--
-- 诚实口径：
--   1. 脱敏规则/等保目录为「系统治理基线目录」（与 V17 敏感词/告警规则同性质），
--      用固定 rule_code/item_code 幂等播种（ON CONFLICT DO NOTHING），不播种业务流水；
--      启停状态可在页面真实翻转，用户自建规则走 serial id。
--   2. 「审计记录」KPI 不写死 12840：统计 AI_PRIVACY 治理动作审计 + 本页导出记录数，
--      全站审计总量在统一审计页查看，本页不编造全站数字。
--   3. 导出哈希为真实哈希：导出时按区间内 audit_log 全链（id/prev/cur/载荷）
--      规范化后 SHA-256，空区间只对范围头取哈希（诚实标注 audit_count=0），不伪造哈希。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_privacy_mask_rule (
    rule_id      BIGSERIAL    PRIMARY KEY,
    rule_code    VARCHAR(32)  NOT NULL UNIQUE,
    field_label  VARCHAR(64)  NOT NULL,
    module_name  VARCHAR(64)  NOT NULL,
    mask_type    VARCHAR(32)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    staff_id     VARCHAR(64),
    staff_name   VARCHAR(64),
    store_code   VARCHAR(32),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_privacy_mask_type CHECK (mask_type IN
        ('phone', 'idcard', 'name', 'amount', 'bankcard', 'address', 'medical', 'email'))
);
COMMENT ON TABLE ai_privacy_mask_rule IS '隐私字段脱敏规则（系统基线目录幂等播种 + 页面可自建/启停）';
COMMENT ON COLUMN ai_privacy_mask_rule.rule_code IS '固定基线码（PM-SEED-xx）；页面自建为空/PM-MAN-序号';
COMMENT ON COLUMN ai_privacy_mask_rule.mask_type IS '脱敏算法类型：phone 手机号 / idcard 身份证 / name 姓名 / amount 金额 / bankcard 银行卡 / address 地址 / medical 诊疗记录 / email 邮箱';
CREATE INDEX IF NOT EXISTS idx_ai_privacy_mask_enabled ON ai_privacy_mask_rule (enabled, rule_id);

CREATE TABLE IF NOT EXISTS ai_privacy_compliance_item (
    item_id      BIGSERIAL    PRIMARY KEY,
    item_code    VARCHAR(32)  NOT NULL UNIQUE,
    label        VARCHAR(200) NOT NULL,
    checked      BOOLEAN      NOT NULL DEFAULT false,
    staff_id     VARCHAR(64),
    staff_name   VARCHAR(64),
    store_code   VARCHAR(32),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE ai_privacy_compliance_item IS '等保三级达标台账（8 项系统目录，达标状态页面真实翻转并审计）';

CREATE TABLE IF NOT EXISTS ai_privacy_export (
    export_id    BIGSERIAL    PRIMARY KEY,
    range_from   DATE         NOT NULL,
    range_to     DATE         NOT NULL,
    audit_count  BIGINT       NOT NULL DEFAULT 0,
    report_hash  VARCHAR(64)  NOT NULL,
    staff_id     VARCHAR(64),
    staff_name   VARCHAR(64),
    store_code   VARCHAR(32),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_privacy_export_range CHECK (range_to >= range_from),
    CONSTRAINT chk_ai_privacy_export_count CHECK (audit_count >= 0)
);
COMMENT ON TABLE ai_privacy_export IS '合规报告导出流水（append-only）：哈希真实覆盖区间内审计链';
COMMENT ON COLUMN ai_privacy_export.report_hash IS 'SHA-256：范围头 + 区间内 audit_log（id/prev_hash/cur_hash/payload）规范化摘要';
CREATE INDEX IF NOT EXISTS idx_ai_privacy_export_created ON ai_privacy_export (export_id DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_privacy_mask_rule' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_privacy_mask_rule_updated_at') THEN
        CREATE TRIGGER trg_ai_privacy_mask_rule_updated_at BEFORE UPDATE ON ai_privacy_mask_rule
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'ai_privacy_compliance_item' AND column_name = 'updated_at')
       AND NOT EXISTS (SELECT 1 FROM pg_trigger
                       WHERE tgname = 'trg_ai_privacy_compliance_item_updated_at') THEN
        CREATE TRIGGER trg_ai_privacy_compliance_item_updated_at BEFORE UPDATE ON ai_privacy_compliance_item
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END;
$$;

-- 系统治理基线目录（与 V17 敏感词播种同性质）：固定码幂等，不随迁移重置用户翻转后的状态
INSERT INTO ai_privacy_mask_rule (rule_code, field_label, module_name, mask_type, enabled) VALUES
    ('PM-SEED-01', '手机号',     'M3 客户中心',   'phone',    true),
    ('PM-SEED-02', '身份证号',   'M3 客户中心',   'idcard',   true),
    ('PM-SEED-03', '真实姓名',   'M3 客户中心',   'name',     true),
    ('PM-SEED-04', '消费金额',   'M6 财务中心',   'amount',   true),
    ('PM-SEED-05', '银行卡号',   'M6 财务中心',   'bankcard', true),
    ('PM-SEED-06', '联系地址',   'M3 客户中心',   'address',  true),
    ('PM-SEED-07', '诊疗记录',   'M4 咨询工作台', 'medical',  false),
    ('PM-SEED-08', '佣金金额',   'M6 财务中心',   'amount',   true)
ON CONFLICT (rule_code) DO NOTHING;

INSERT INTO ai_privacy_compliance_item (item_code, label, checked) VALUES
    ('PC-SEED-01', '安全物理环境 — 机房访问控制、防火防水', true),
    ('PC-SEED-02', '安全通信网络 — 国密 TLS 1.3 强制加密', true),
    ('PC-SEED-03', '安全区域边界 — 入侵检测 / 访问控制列表', true),
    ('PC-SEED-04', '安全计算环境 — 身份鉴别、权限最小化', true),
    ('PC-SEED-05', '安全管理中心 — 集中审计、集中管控', true),
    ('PC-SEED-06', 'AI 数据本地隔离 — 训练数据不出域', true),
    ('PC-SEED-07', '隐私字段全站脱敏 — 展示层 / 接口层双脱敏', false),
    ('PC-SEED-08', '审计日志 append-only — WORM 存储、不可篡改', true)
ON CONFLICT (item_code) DO NOTHING;
