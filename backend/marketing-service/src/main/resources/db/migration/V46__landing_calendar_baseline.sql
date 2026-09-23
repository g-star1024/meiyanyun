-- ============================================================
-- V46 落地页 / 营销日历基线（P5-B88）
-- 三表：landing_page（落地页）/ calendar_node（日历节点）/ calendar_schedule（活动排期）
-- 说明：flyway_schema_history 全库共享，版本号全局递增（全局最大 V45 后取 V46）。
-- 幂等可重入：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS。
-- 不建物理外键：calendar_schedule.node_id 逻辑引用 calendar_node.node_id（见列 COMMENT）。
-- 金额口径：estimated_revenue_cents bigint 存「分」（铁律 2，前端活规格为元，换算 ×100 在适配层）。
-- 多门店口径（D2-A）：landing_page / calendar_node 为全连锁资产无门店列；
--   calendar_schedule.store_code NULL=全连锁、填值=单店（对齐 customer_grant 单值 store_code 先例）。
-- 指标口径（D4-A）：visits / leads / ab_enabled / variants v1 入库持久化（种子为演示数据），
--   真实采集埋点（表单提交回写）留 v2 专项。
-- ============================================================

CREATE TABLE IF NOT EXISTS landing_page (
    page_id      VARCHAR(24)  NOT NULL PRIMARY KEY,
    page_name    VARCHAR(64)  NOT NULL,
    template     VARCHAR(16)  NOT NULL,
    status       VARCHAR(10)  NOT NULL DEFAULT 'DRAFT',
    headline     VARCHAR(128),
    subtitle     VARCHAR(256),
    project      VARCHAR(64),
    form_fields  TEXT,
    blocks       TEXT,
    visits       BIGINT       NOT NULL DEFAULT 0,
    leads        BIGINT       NOT NULL DEFAULT 0,
    ab_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
    variants     TEXT,
    client_token VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_landing_page_template CHECK (template IN ('NEWBIE', 'PROJECT', 'FESTIVAL', 'MEMBER', 'BRAND')),
    CONSTRAINT chk_landing_page_status   CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE'))
);

COMMENT ON TABLE  landing_page IS '落地页（P5-B88）：模板五类 NEWBIE/PROJECT/FESTIVAL/MEMBER/BRAND，状态三态 DRAFT/PUBLISHED/OFFLINE；form_fields/blocks/variants 为 JSON 数组文本；visits/leads v1 为种子演示数、真实采集留 v2';
COMMENT ON COLUMN landing_page.page_id IS '落地页号：LP + yyyyMMdd + - + 6 位当日序号（BizNoGenerator），种子固定号 LP-SEED-xxx';
COMMENT ON COLUMN landing_page.form_fields IS '表单字段 JSON 数组文本，如 ["姓名","手机","意向项目"]';
COMMENT ON COLUMN landing_page.blocks IS '可视化组件块 JSON 数组文本：[{"id","type":"HERO|TITLE|PROJECT|FORM|BUTTON","label"}]，顺序即页面结构';
COMMENT ON COLUMN landing_page.variants IS 'A/B 变体 JSON 数组文本：[{"name","visits","leads"}]，ab_enabled=false 时为空数组';
COMMENT ON COLUMN landing_page.client_token IS '创建幂等令牌：前端每次表单会话生成，重复提交命中即返回已有行';

CREATE UNIQUE INDEX IF NOT EXISTS uk_landing_page_client_token
    ON landing_page (client_token) WHERE client_token IS NOT NULL;

CREATE TABLE IF NOT EXISTS calendar_node (
    node_id    VARCHAR(24)  NOT NULL PRIMARY KEY,
    node_date  DATE         NOT NULL,
    title      VARCHAR(64)  NOT NULL,
    node_type  VARCHAR(16)  NOT NULL,
    node_desc  VARCHAR(256),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_calendar_node_type CHECK (node_type IN ('member', 'festival', 'campaign'))
);

COMMENT ON TABLE  calendar_node IS '营销日历节点（P5-B88）：会员日 member / 节日 festival / 活动 campaign 三类，全连锁资产（D2-A）';
COMMENT ON COLUMN calendar_node.node_id IS '节点号：CN + yyyyMMdd + - + 6 位当日序号，种子固定号 CN-SEED-xxx';

CREATE INDEX IF NOT EXISTS idx_calendar_node_date ON calendar_node (node_date);

CREATE TABLE IF NOT EXISTS calendar_schedule (
    schedule_id             VARCHAR(24)  NOT NULL PRIMARY KEY,
    node_id                 VARCHAR(24)  NOT NULL,
    node_date               DATE         NOT NULL,
    schedule_name           VARCHAR(64)  NOT NULL,
    benefit_desc            VARCHAR(256),
    coupon_ids              TEXT,
    points_reward           INTEGER      NOT NULL DEFAULT 0,
    start_date              DATE         NOT NULL,
    end_date                DATE         NOT NULL,
    channels                TEXT,
    copy_text               VARCHAR(500),
    status                  VARCHAR(10)  NOT NULL DEFAULT 'DRAFT',
    estimated_revenue_cents BIGINT       NOT NULL DEFAULT 0,
    store_code              VARCHAR(16),
    created_by              VARCHAR(32),
    client_token            VARCHAR(64),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_calendar_schedule_status CHECK (status IN ('DRAFT', 'SCHEDULED', 'RUNNING', 'ENDED')),
    CONSTRAINT chk_calendar_schedule_dates  CHECK (end_date >= start_date)
);

COMMENT ON TABLE  calendar_schedule IS '日历活动排期（P5-B88）：状态四态 DRAFT/SCHEDULED/RUNNING/ENDED，由 CalendarScheduleJob 按日期日更推进；node_id 逻辑引用 calendar_node.node_id（不建物理外键，节点删除不级联）';
COMMENT ON COLUMN calendar_schedule.schedule_id IS '排期号：CS + yyyyMMdd + - + 6 位当日序号，种子固定号 CS-SEED-xxx';
COMMENT ON COLUMN calendar_schedule.node_id IS '逻辑引用 calendar_node.node_id';
COMMENT ON COLUMN calendar_schedule.coupon_ids IS '关联券模板号 JSON 数组文本，如 ["CPN20260901-000001"]';
COMMENT ON COLUMN calendar_schedule.channels IS '推送渠道 JSON 数组文本：SMS 短信 / WECOM 企微 / WECHAT_MP 公众号';
COMMENT ON COLUMN calendar_schedule.estimated_revenue_cents IS '预估成交额（分）；前端活规格为元，×100 在 stores 适配层（铁律 2）';
COMMENT ON COLUMN calendar_schedule.store_code IS '门店码：NULL=全连锁，填值=单店排期（D2-A）';
COMMENT ON COLUMN calendar_schedule.client_token IS '创建幂等令牌：前端每次表单会话生成，重复提交命中即返回已有行';

CREATE INDEX IF NOT EXISTS idx_calendar_schedule_node  ON calendar_schedule (node_id);
CREATE INDEX IF NOT EXISTS idx_calendar_schedule_dates ON calendar_schedule (start_date, end_date);
CREATE INDEX IF NOT EXISTS idx_calendar_schedule_store ON calendar_schedule (store_code);
CREATE UNIQUE INDEX IF NOT EXISTS uk_calendar_schedule_client_token
    ON calendar_schedule (client_token) WHERE client_token IS NOT NULL;
