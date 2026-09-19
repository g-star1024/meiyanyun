-- V38__prepay_monitor.sql
-- 美研云门店中台 - B63 卡3 L85 预收合规监控（规则实体＋账龄扫描事件＋站内信联动）
-- 创建时间: 2026-09-19
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   L85 前预收池只有 prepay_pool 单行聚合（无门店/账龄/客户维度），FinPrepayView
--   三类告警（大额退款待核销/沉睡沉淀/勾稽）纯前端本地派生，finance-service 零定时任务。
--   本迁移落两张表支撑 finance 首引 @EnableScheduling 的日终合规扫描：
--     prepay_monitor_rule  规则（阈值/级别/行级启用开关/门店白名单灰度）
--     prepay_monitor_event 扫描命中事件（同规则同店 OPEN 幂等，回转 RESOLVED 后可再开）
--   规则三类：
--     DEPOSIT_AGE    预收充值沉淀账龄（fund_entry RF-DEPOSIT/IN.occurred_at 超阈值天）
--     REFUND_PENDING 退款待核销金额（台账退款行按门店聚合，超阈值分）
--     DORMANT_CARD   沉睡卡沉淀（member_card 退卡/已用完映射 DORMANT 口径，超阈值分）
--
-- 首启安全（三层防雪崩）：
--   1. 本迁移纯 DDL 零 INSERT——规则表零行，PrepayMonitorJob 首轮空跑只落一条审计，
--      绝不产生事件/站内信；
--   2. 规则行级 enabled 默认 false，且 store_code 非空时仅对白名单门店生效（NULL=全店）；
--   3. 同规则同店仅允许一条 OPEN 事件（部分唯一索引），新发事件才触发 txn
--      compliance-alert，其 COMPLIANCE:{bizRef}:{staffId}:{level} 幂等键第三层兜底。
--
-- 幂等：CREATE TABLE/INDEX IF NOT EXISTS，双库（meiyun_core / meiyun_seed）各自
--   Flyway 独立应用一次，可重入；迁移后 JPA ddl-auto=update 对本表 no-op。

CREATE TABLE IF NOT EXISTS prepay_monitor_rule (
    code                VARCHAR(16)   PRIMARY KEY,          -- 规则编码（短码，如 AGE180/REF50K/DORMANT；bizRef ≤32 约束）
    rule_name           VARCHAR(64)   NOT NULL,             -- 规则名称（中文）
    type                VARCHAR(16)   NOT NULL,             -- DEPOSIT_AGE 账龄沉淀 / REFUND_PENDING 退款待核销 / DORMANT_CARD 沉睡卡沉淀
    store_code          VARCHAR(16),                        -- 门店白名单：NULL=全店；非空仅该门店生效（灰度）
    threshold_value     BIGINT        NOT NULL,             -- 阈值（与 unit 搭配：天数 或 分）
    threshold_unit      VARCHAR(8)    NOT NULL,             -- DAY 天 / FEN 分
    level               VARCHAR(8)    NOT NULL,             -- HIGH / MEDIUM / LOW（映射 CRITICAL/WARN 站内信）
    enabled             BOOLEAN       NOT NULL DEFAULT false, -- 行级启用开关，默认关（首启安全第二层）
    remark              VARCHAR(256),                       -- 备注（中文）
    created_by          VARCHAR(16)   NOT NULL DEFAULT 'system',
    updated_by          VARCHAR(16)   NOT NULL DEFAULT 'system',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_prepay_monitor_rule_type CHECK (type IN ('DEPOSIT_AGE','REFUND_PENDING','DORMANT_CARD')),
    CONSTRAINT chk_prepay_monitor_rule_unit CHECK (threshold_unit IN ('DAY','FEN')),
    CONSTRAINT chk_prepay_monitor_rule_level CHECK (level IN ('HIGH','MEDIUM','LOW')),
    CONSTRAINT chk_prepay_monitor_rule_threshold CHECK (threshold_value > 0)
);
CREATE INDEX IF NOT EXISTS idx_prepay_monitor_rule_type ON prepay_monitor_rule(type);
CREATE INDEX IF NOT EXISTS idx_prepay_monitor_rule_enabled ON prepay_monitor_rule(enabled);
CREATE INDEX IF NOT EXISTS idx_prepay_monitor_rule_store ON prepay_monitor_rule(store_code);

CREATE TABLE IF NOT EXISTS prepay_monitor_event (
    event_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idem_key            VARCHAR(96)   NOT NULL,             -- 技术幂等键 PM:{ruleCode}:{storeCode}:R{轮次}
    rule_code           VARCHAR(16)   NOT NULL,             -- 命中规则
    store_code          VARCHAR(16)   NOT NULL,             -- 命中门店
    type                VARCHAR(16)   NOT NULL,             -- 快照规则类型（规则改型不影响历史事件解读）
    level               VARCHAR(8)    NOT NULL,             -- 快照级别 HIGH/MEDIUM/LOW
    title               VARCHAR(128)  NOT NULL,             -- 告警标题（透传 txn，截 128）
    content             VARCHAR(500)  NOT NULL,             -- 告警内容（透传 txn，截 500）
    amount_fen          BIGINT        NOT NULL DEFAULT 0,   -- 命中金额（分；账龄类为沉淀金额，无金额类 0）
    biz_ref             VARCHAR(32)   NOT NULL,             -- 短业务键 PM{yyMMdd}:{ruleCode}:{storeCode}（≤32 供 COMPLIANCE: 幂等）
    status              VARCHAR(8)    NOT NULL DEFAULT 'OPEN', -- OPEN 待整改 / RESOLVED 已回转
    fired_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,                        -- 下一轮扫描不再命中时回转时间
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_prepay_monitor_event_type CHECK (type IN ('DEPOSIT_AGE','REFUND_PENDING','DORMANT_CARD')),
    CONSTRAINT chk_prepay_monitor_event_level CHECK (level IN ('HIGH','MEDIUM','LOW')),
    CONSTRAINT chk_prepay_monitor_event_status CHECK (status IN ('OPEN','RESOLVED')),
    CONSTRAINT chk_prepay_monitor_event_amount CHECK (amount_fen >= 0)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_prepay_monitor_event_idem ON prepay_monitor_event(idem_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_prepay_monitor_event_open
    ON prepay_monitor_event(rule_code, store_code)
    WHERE status = 'OPEN';
CREATE INDEX IF NOT EXISTS idx_prepay_monitor_event_store ON prepay_monitor_event(store_code);
CREATE INDEX IF NOT EXISTS idx_prepay_monitor_event_status ON prepay_monitor_event(status);
CREATE INDEX IF NOT EXISTS idx_prepay_monitor_event_fired ON prepay_monitor_event(fired_at);
