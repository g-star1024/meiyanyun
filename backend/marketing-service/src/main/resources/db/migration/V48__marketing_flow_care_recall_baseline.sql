-- ============================================================
-- V48 营销侧随访 Flow / 关怀 / 复诊召回基线（P5-B90）
-- 四表：automation_rule（Flow 规则）/ automation_log（Flow 执行日志，idem_key 幂等防重）
--      / care_task（关怀任务）/ recall（复诊召回）
-- 设计定案：docs/DESIGN-P5-B90-MARKETING-FLOW-CARE-RECALL-2026-09-24.md（D1-D5）
-- 说明：flyway_schema_history 全库共享，版本号全局递增（B89 已占 V47=referral campaign，本批取 V48）。
-- 幂等可重入：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS，种子 INSERT WHERE NOT EXISTS。
-- 不建物理外键：automation_log.rule_no / care_task.rule_no / recall.rule_no 逻辑引用
--   automation_rule.rule_no（见列 COMMENT）。
-- 单据号口径：rule_no=AR / care_no=CARE / recall_no=RC + yyyyMMdd + - + 6 位当日序号
--   （BizNoGenerator 既有工具类 6 位口径；DESIGN §2「4 位序号」表述以既有工具类为准）。
-- 多门店口径（铁律-1-D）：三表 store_code NULL=全连锁、填值=按客户归属门店过滤
--   （对齐 customer_grant / calendar_schedule 单值 store_code 先例）。
-- B90 卡1 增补：recall 较 DESIGN §2.4 增 notified_by / notified_at / skip_reason 三列，
--   前端契约（recall.ts notifiedByName/notifiedAt/skipReason）直出，避免 timeline 解析。
-- ============================================================

CREATE TABLE IF NOT EXISTS automation_rule (
    id               BIGSERIAL    NOT NULL PRIMARY KEY,
    rule_no          VARCHAR(32)  NOT NULL,
    name             VARCHAR(64)  NOT NULL,
    trigger_type     VARCHAR(32)  NOT NULL,
    trigger_config   JSONB,
    condition_config JSONB,
    action_type      VARCHAR(32)  NOT NULL,
    action_config    JSONB,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    store_code       VARCHAR(32),
    created_by       VARCHAR(32),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_automation_rule_no UNIQUE (rule_no),
    CONSTRAINT chk_automation_rule_trigger CHECK (trigger_type IN ('BIRTHDAY', 'DORMANT_DAYS', 'VISIT_GAP_DAYS')),
    CONSTRAINT chk_automation_rule_action  CHECK (action_type IN ('CREATE_CARE_TASK', 'CREATE_RECALL'))
);

COMMENT ON TABLE  automation_rule IS '营销自动化 Flow 规则（P5-B90）：Trigger-Condition-Action 内建轻量引擎（B69 定案不采购外部 SaaS）；trigger_config={daysBefore}/{dormantDays}/{gapDays}，condition_config={levels:[],tags:[]} 预留，action_config={careType,channel,contentTemplate}/{method,reason}';
COMMENT ON COLUMN automation_rule.rule_no IS '规则号：AR + yyyyMMdd + - + 6 位当日序号（BizNoGenerator），V48 种子固定号 AR-SEED-xxx';
COMMENT ON COLUMN automation_rule.trigger_config IS '触发配置 JSONB：BIRTHDAY={daysBefore}，DORMANT_DAYS={dormantDays}，VISIT_GAP_DAYS={gapDays}';
COMMENT ON COLUMN automation_rule.condition_config IS '条件配置 JSONB：{levels:[],tags:[]} 客户等级/标签预留，v1 引擎做空值安全基本匹配';
COMMENT ON COLUMN automation_rule.action_config IS '动作配置 JSONB：CREATE_CARE_TASK={careType,channel,contentTemplate}，CREATE_RECALL={method,reason}';
COMMENT ON COLUMN automation_rule.store_code IS '门店码：NULL=全部门店，填值=按客户归属门店过滤（铁律-1-D）';

CREATE TABLE IF NOT EXISTS automation_log (
    id           BIGSERIAL   NOT NULL PRIMARY KEY,
    rule_no      VARCHAR(32) NOT NULL,
    customer_id  VARCHAR(16) NOT NULL,
    trigger_date DATE        NOT NULL,
    action_type  VARCHAR(32) NOT NULL,
    action_ref   VARCHAR(64),
    status       VARCHAR(16) NOT NULL,
    message      VARCHAR(500),
    idem_key     VARCHAR(128) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_automation_log_idem UNIQUE (idem_key),
    CONSTRAINT chk_automation_log_status CHECK (status IN ('SUCCESS', 'SKIPPED', 'FAILED'))
);

COMMENT ON TABLE  automation_log IS 'Flow 执行日志（P5-B90）：idem_key={ruleNo}:{customerId}:{triggerDate} 唯一约束幂等防重核心；status 三态 SUCCESS/SKIPPED/FAILED，FAILED 下轮自愈';
COMMENT ON COLUMN automation_log.rule_no IS '逻辑引用 automation_rule.rule_no（不建物理外键）';
COMMENT ON COLUMN automation_log.trigger_date IS '触发业务日（业务时区 +8）';
COMMENT ON COLUMN automation_log.action_ref IS '动作产物单号：生成的 care_no / recall_no';
COMMENT ON COLUMN automation_log.message IS '跳过 / 失败原因（中文）';
COMMENT ON COLUMN automation_log.idem_key IS '幂等键：{ruleNo}:{customerId}:{triggerDate}，查重即跳';

CREATE INDEX IF NOT EXISTS idx_automation_log_rule_date ON automation_log (rule_no, trigger_date);
CREATE INDEX IF NOT EXISTS idx_automation_log_customer  ON automation_log (customer_id);

CREATE TABLE IF NOT EXISTS care_task (
    id                BIGSERIAL   NOT NULL PRIMARY KEY,
    care_no           VARCHAR(32) NOT NULL,
    customer_id       VARCHAR(16),
    customer_name     VARCHAR(64),
    type              VARCHAR(16) NOT NULL,
    channel           VARCHAR(16) NOT NULL,
    content           VARCHAR(500),
    plan_date         DATE        NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reached           BOOLEAN     NOT NULL DEFAULT FALSE,
    converted_booking BOOLEAN     NOT NULL DEFAULT FALSE,
    sent_at           TIMESTAMPTZ,
    reached_at        TIMESTAMPTZ,
    rule_no           VARCHAR(32),
    store_code        VARCHAR(32),
    created_by        VARCHAR(32),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_care_task_no UNIQUE (care_no),
    CONSTRAINT chk_care_task_type    CHECK (type IN ('BIRTHDAY', 'HOLIDAY', 'REPURCHASE', 'REACTIVATE')),
    CONSTRAINT chk_care_task_channel CHECK (channel IN ('SMS', 'WECHAT', 'PHONE')),
    CONSTRAINT chk_care_task_status  CHECK (status IN ('PENDING', 'SENT', 'REACHED'))
);

COMMENT ON TABLE  care_task IS '关怀任务（P5-B90，/m3-care 切真）：四型 BIRTHDAY/HOLIDAY/REPURCHASE/REACTIVATE，三渠道 SMS/WECHAT/PHONE，三态 PENDING/SENT/REACHED；send 经 PushService consent 门控（撤回→skipped 不落 record）';
COMMENT ON COLUMN care_task.care_no IS '关怀单号：CARE + yyyyMMdd + - + 6 位当日序号（BizNoGenerator）';
COMMENT ON COLUMN care_task.customer_name IS '客户姓名冗余列：创建时经 CustomerDirectoryClient 解析落列';
COMMENT ON COLUMN care_task.content IS '关怀文案（经 ForbiddenWordService 校验，≤500 字）';
COMMENT ON COLUMN care_task.plan_date IS '计划关怀日（KPI「本月待关怀」锚，业务时区 +8）';
COMMENT ON COLUMN care_task.converted_booking IS '带来预约标记（KPI「带来预约」锚）';
COMMENT ON COLUMN care_task.rule_no IS '来源规则：逻辑引用 automation_rule.rule_no，NULL=人工创建';
COMMENT ON COLUMN care_task.store_code IS '门店码：NULL=全连锁，填值=客户归属门店（铁律-1-D）';

CREATE INDEX IF NOT EXISTS idx_care_task_status_plan ON care_task (status, plan_date);
CREATE INDEX IF NOT EXISTS idx_care_task_customer    ON care_task (customer_id);

CREATE TABLE IF NOT EXISTS recall (
    id               BIGSERIAL   NOT NULL PRIMARY KEY,
    recall_no        VARCHAR(32) NOT NULL,
    customer_id      VARCHAR(16),
    customer_name    VARCHAR(64),
    source           VARCHAR(16) NOT NULL,
    reason           VARCHAR(200) NOT NULL,
    related_emr_no   VARCHAR(32),
    related_order_no VARCHAR(32),
    last_visit_date  DATE,
    due_date         DATE        NOT NULL,
    method           VARCHAR(16) NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    notified_by      VARCHAR(32),
    notified_at      TIMESTAMPTZ,
    customer_reply   VARCHAR(500),
    confirmed_date   DATE,
    skip_reason      VARCHAR(200),
    note             VARCHAR(500),
    timeline         JSONB,
    rule_no          VARCHAR(32),
    store_code       VARCHAR(32),
    created_by       VARCHAR(32),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_recall_no UNIQUE (recall_no),
    CONSTRAINT chk_recall_source CHECK (source IN ('DOCTOR_ADVICE', 'COURSE_FOLLOW', 'SYSTEM_AUTO', 'MANUAL')),
    CONSTRAINT chk_recall_method CHECK (method IN ('PHONE', 'WECHAT', 'SMS', 'IN_STORE')),
    CONSTRAINT chk_recall_status CHECK (status IN ('PENDING', 'NOTIFIED', 'CONFIRMED', 'BOOKED', 'SKIPPED'))
);

COMMENT ON TABLE  recall IS '复诊召回（P5-B90，/recall 切真）：状态机服务端唯一权威 PENDING→[NOTIFIED,SKIPPED]、NOTIFIED→[CONFIRMED,BOOKED,SKIPPED,PENDING 改期]、CONFIRMED→[BOOKED,SKIPPED]、BOOKED/SKIPPED 终态，非法转移 409；与 txn followup 表零共享（D5 诊疗边界）';
COMMENT ON COLUMN recall.recall_no IS '召回单号：RC + yyyyMMdd + - + 6 位当日序号（BizNoGenerator）';
COMMENT ON COLUMN recall.customer_name IS '客户姓名冗余列：创建时经 CustomerDirectoryClient 解析落列';
COMMENT ON COLUMN recall.related_emr_no IS '关联病历号（可空，逻辑引用不建外键）';
COMMENT ON COLUMN recall.related_order_no IS '关联订单号（可空，逻辑引用不建外键）';
COMMENT ON COLUMN recall.last_visit_date IS '末次到店 / 治疗日';
COMMENT ON COLUMN recall.due_date IS '应召回日（超期 / 今日待提醒 / 3 日到期 KPI 锚，业务时区 +8）';
COMMENT ON COLUMN recall.notified_by IS '提醒操作人（B90 卡1 增补：前端 notifiedByName 契约直出，避免 timeline 解析）';
COMMENT ON COLUMN recall.notified_at IS '提醒时间（B90 卡1 增补：前端 notifiedAt 契约直出）';
COMMENT ON COLUMN recall.skip_reason IS '跳过原因（B90 卡1 增补：前端 skipReason 契约直出）';
COMMENT ON COLUMN recall.timeline IS '时间线 JSONB：[{at,by,action,detail}]，每次状态转移 append 一条';
COMMENT ON COLUMN recall.rule_no IS '来源规则：逻辑引用 automation_rule.rule_no，NULL=人工创建（SYSTEM_AUTO 来源时非空）';
COMMENT ON COLUMN recall.store_code IS '门店码：NULL=全连锁，填值=客户归属门店（铁律-1-D）';

CREATE INDEX IF NOT EXISTS idx_recall_status_due ON recall (status, due_date);
CREATE INDEX IF NOT EXISTS idx_recall_customer   ON recall (customer_id);

-- ── V48 播种：默认三规则（D4 Trigger 首期子集），enabled=true、store_code=NULL 全连锁 ──
INSERT INTO automation_rule (rule_no, name, trigger_type, trigger_config, condition_config, action_type, action_config, enabled, store_code, created_by)
SELECT 'AR-SEED-BIRTHDAY', '生日关怀', 'BIRTHDAY',
       '{"daysBefore":0}'::jsonb, '{"levels":[],"tags":[]}'::jsonb,
       'CREATE_CARE_TASK',
       '{"careType":"BIRTHDAY","channel":"WECHAT","contentTemplate":"生日海报 + 到店券，企微一键推送。含本月专属项目优惠与免费皮肤检测名额。"}'::jsonb,
       TRUE, NULL, 'SYSTEM'
WHERE NOT EXISTS (
    SELECT 1 FROM automation_rule WHERE rule_no = 'AR-SEED-BIRTHDAY'
);

INSERT INTO automation_rule (rule_no, name, trigger_type, trigger_config, condition_config, action_type, action_config, enabled, store_code, created_by)
SELECT 'AR-SEED-DORMANT', '沉睡客户唤醒', 'DORMANT_DAYS',
       '{"dormantDays":90}'::jsonb, '{"levels":[],"tags":[]}'::jsonb,
       'CREATE_CARE_TASK',
       '{"careType":"REACTIVATE","channel":"SMS","contentTemplate":"好久不见！我们为您准备了 200 元回归券，7 天内到店即可使用，期待您的光临。"}'::jsonb,
       TRUE, NULL, 'SYSTEM'
WHERE NOT EXISTS (
    SELECT 1 FROM automation_rule WHERE rule_no = 'AR-SEED-DORMANT'
);

INSERT INTO automation_rule (rule_no, name, trigger_type, trigger_config, condition_config, action_type, action_config, enabled, store_code, created_by)
SELECT 'AR-SEED-VISITGAP', '复诊召回自动化', 'VISIT_GAP_DAYS',
       '{"gapDays":30}'::jsonb, '{"levels":[],"tags":[]}'::jsonb,
       'CREATE_RECALL',
       '{"method":"WECHAT","reason":"距上次到店已满 30 天，疗程复诊提醒"}'::jsonb,
       TRUE, NULL, 'SYSTEM'
WHERE NOT EXISTS (
    SELECT 1 FROM automation_rule WHERE rule_no = 'AR-SEED-VISITGAP'
);
