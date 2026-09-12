-- =============================================================================
-- V17__ai_quota_security_alert.sql
-- B44 AI 治理二期：调用配额 + 敏感词/越权提示词过滤 + 监控告警规则
--
-- 版本链：全库共享 flyway_schema_history，V15/V16 为 ai-service，本脚本占用 V17。
-- DDL 全部 CREATE TABLE IF NOT EXISTS，可在历史 ddl-auto=update 环境重入。
--
-- 用量真相约定：
--   ai_quota 只存额度（日/月次数上限），不存计数列；实际用量以 append-only 的
--   ai_invoke_log 为唯一来源，调用前按 +08:00 时间窗实时 count 比对，超限 429 拒绝。
--
-- 表清单：
--   ai_quota          配额（维度 FEATURE/MODEL/GLOBAL × 目标编码，日/月次数额度）
--   ai_sensitive_word 敏感词库（BANNED 违禁内容 / INJECTION 越权提示词）
--   ai_alert_rule     监控告警规则（延迟/错误率/调用量/可用率/额度水位阈值）
-- =============================================================================

-- 1. 配额 ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_quota (
    quota_id        BIGSERIAL    PRIMARY KEY,
    quota_scope     VARCHAR(16)  NOT NULL,
    target_code     VARCHAR(128) NOT NULL,
    daily_limit     INTEGER,
    monthly_limit   INTEGER,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_quota_scope_target UNIQUE (quota_scope, target_code),
    CONSTRAINT chk_ai_quota_scope CHECK (quota_scope IN ('FEATURE', 'MODEL', 'GLOBAL')),
    CONSTRAINT chk_ai_quota_daily   CHECK (daily_limit   IS NULL OR daily_limit   >= 0),
    CONSTRAINT chk_ai_quota_monthly CHECK (monthly_limit IS NULL OR monthly_limit >= 0)
);
COMMENT ON TABLE  ai_quota IS 'AI 调用配额（额度配置，用量实时聚合自 ai_invoke_log，不设计数列）';
COMMENT ON COLUMN ai_quota.quota_scope   IS '配额维度：FEATURE 按功能 / MODEL 按模型 / GLOBAL 全局兜底';
COMMENT ON COLUMN ai_quota.target_code   IS '维度目标编码：功能编码/模型编码；GLOBAL 固定为 *';
COMMENT ON COLUMN ai_quota.daily_limit   IS '每日调用次数上限，NULL=不限';
COMMENT ON COLUMN ai_quota.monthly_limit IS '每月调用次数上限，NULL=不限';

-- 2. 敏感词库 -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_sensitive_word (
    word_id     BIGSERIAL    PRIMARY KEY,
    word        VARCHAR(128) NOT NULL,
    category    VARCHAR(16)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_sensitive_word UNIQUE (word, category),
    CONSTRAINT chk_ai_sensitive_category CHECK (category IN ('BANNED', 'INJECTION'))
);
COMMENT ON TABLE  ai_sensitive_word IS 'AI 敏感词库（出站提示词合规过滤）';
COMMENT ON COLUMN ai_sensitive_word.category IS '词类：BANNED 违禁内容 / INJECTION 越权提示词（prompt injection）';

INSERT INTO ai_sensitive_word (word, category) VALUES
    ('伪造病历', 'BANNED'),
    ('非法行医', 'BANNED'),
    ('刷单返利', 'BANNED'),
    ('逃税漏税', 'BANNED'),
    ('虚开发票', 'BANNED'),
    ('忽略之前的指令', 'INJECTION'),
    ('忽略以上指令', 'INJECTION'),
    ('ignore previous instructions', 'INJECTION'),
    ('ignore all previous', 'INJECTION'),
    ('你现在是系统管理员', 'INJECTION'),
    ('进入开发者模式', 'INJECTION'),
    ('泄露系统提示词', 'INJECTION'),
    ('DROP TABLE', 'INJECTION')
ON CONFLICT (word, category) DO NOTHING;

-- 3. 监控告警规则 -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_alert_rule (
    rule_id        BIGSERIAL     PRIMARY KEY,
    rule_code      VARCHAR(64)   NOT NULL,
    rule_name      VARCHAR(128)  NOT NULL,
    metric         VARCHAR(32)   NOT NULL,
    compare_op     VARCHAR(4)    NOT NULL DEFAULT '>',
    threshold_num  NUMERIC(12,2) NOT NULL,
    window_minutes INTEGER       NOT NULL DEFAULT 1440,
    notify_channel VARCHAR(128)  NOT NULL DEFAULT '站内信',
    enabled        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_alert_rule_code UNIQUE (rule_code),
    CONSTRAINT chk_ai_alert_metric CHECK (metric IN ('LATENCY_P99', 'ERROR_RATE', 'CALL_COUNT', 'SUCCESS_RATE', 'QUOTA_WATERMARK')),
    CONSTRAINT chk_ai_alert_op CHECK (compare_op IN ('>', '<'))
);
COMMENT ON TABLE  ai_alert_rule IS 'AI 监控告警规则（请求时实时评估命中状态，无定时任务）';
COMMENT ON COLUMN ai_alert_rule.metric        IS '指标：LATENCY_P99 P99延迟毫秒 / ERROR_RATE 错误率% / CALL_COUNT 窗口调用量 / SUCCESS_RATE 成功率% / QUOTA_WATERMARK 配额水位%';
COMMENT ON COLUMN ai_alert_rule.compare_op    IS '比较方向：> 超阈或 < 跌破';
COMMENT ON COLUMN ai_alert_rule.threshold_num IS '阈值（单位随 metric：ms / % / 次 / % / %）';
COMMENT ON COLUMN ai_alert_rule.window_minutes IS '评估窗口（分钟），默认 1440=当天';

INSERT INTO ai_alert_rule (rule_code, rule_name, metric, compare_op, threshold_num, window_minutes, notify_channel) VALUES
    ('ALERT_P99_LATENCY', 'P99 延迟过高',      'LATENCY_P99',     '>', 200,   1440, '站内信'),
    ('ALERT_ERROR_RATE',  '调用错误率超标',    'ERROR_RATE',      '>', 1,     1440, '站内信'),
    ('ALERT_CALL_SURGE',  '调用量突增',        'CALL_COUNT',      '>', 500,   1440, '站内信'),
    ('ALERT_AVAILABILITY','供应商可用率过低',  'SUCCESS_RATE',    '<', 99.9,  1440, '站内信'),
    ('ALERT_QUOTA_WATER', '配额水位告警',      'QUOTA_WATERMARK', '>', 80,    1440, '站内信')
ON CONFLICT (rule_code) DO NOTHING;
