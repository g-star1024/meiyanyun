-- ============================================================
-- V55 跟进任务表（M3-B2 / DESIGN-M3 §3 D1/D3/D8：任务 M3-08 切真＋AI 干预下发接线）
-- 单表：follow_task（跟进任务——人工创建＋AI 下发双通道）
-- 说明：flyway_schema_history 全库共享，版本号全局递增（全局最大 V54 后取 V55）。
-- 幂等可重入：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS。
-- 不建物理外键：customer_id / source_id 均为逻辑引用（见列 COMMENT）。
-- AI 下发幂等：idem_key 部分唯一索引（照 V52 uk_touch_event_client_token_type 先例），
--   idem_key = source:sourceId:customerId:date（D8，照 Flow idemKey=ruleNo:customerId:today 先例），
--   重放/并发撞唯一约束 → 捕 DataIntegrityViolationException 直返既有任务，不重复建单。
-- ddl-auto=validate 硬约束（B48 卡3 收口）：本 DDL 与 FollowTask 实体映射逐列对齐。
-- ============================================================

CREATE TABLE IF NOT EXISTS follow_task (
    id             BIGSERIAL    PRIMARY KEY,
    follow_no      VARCHAR(32)  NOT NULL UNIQUE,
    customer_id    VARCHAR(16),
    customer_name  VARCHAR(64)  NOT NULL,
    customer_level VARCHAR(16),
    type           VARCHAR(16)  NOT NULL,
    content        VARCHAR(500),
    deadline       TIMESTAMPTZ,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    priority       VARCHAR(8)   NOT NULL DEFAULT 'MEDIUM',
    assignee       VARCHAR(64),
    completed_at   TIMESTAMPTZ,
    logs           JSONB,
    source         VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    source_id      VARCHAR(64),
    idem_key       VARCHAR(128),
    store_code     VARCHAR(16),
    created_by     VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_follow_task_type     CHECK (type IN ('PHONE', 'WECHAT', 'IN_STORE', 'BIRTHDAY', 'POST_OP', 'CONTENT')),
    CONSTRAINT chk_follow_task_status   CHECK (status IN ('PENDING', 'DONE', 'OVERDUE')),
    CONSTRAINT chk_follow_task_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT chk_follow_task_source   CHECK (source IN ('MANUAL', 'CHURN', 'REPURCHASE'))
);

COMMENT ON TABLE  follow_task IS '跟进任务（M3-B2 / DESIGN-M3 §3：任务 M3-08 切真＋AI 干预下发接线）：人工创建（MANUAL）＋AI 下发（CHURN 流失干预 / REPURCHASE 复购关怀）双通道；任务页列表/KPI/状态机直读本表';
COMMENT ON COLUMN follow_task.follow_no IS '任务单号：FT+yyyyMMdd-6位当日序号，BizNoGenerator 生成（DB 当日最大号+1，禁内存序列）';
COMMENT ON COLUMN follow_task.customer_id IS '客户号（逻辑引用，可空）：AI 下发时由 ai 侧带入；人工创建按姓名免档案';
COMMENT ON COLUMN follow_task.customer_name IS '客户姓名（冗余列）：创建时落列，查询免跨域';
COMMENT ON COLUMN follow_task.customer_level IS '客户等级（自由文本零 chk）：种子值 普通/钻石/黄金/白金/金卡/银卡，AI 下发可缺省';
COMMENT ON COLUMN follow_task.type IS '任务类型六值：PHONE 电话回访 / WECHAT 企微跟进 / IN_STORE 到店提醒 / BIRTHDAY 生日关怀 / POST_OP 术后回访 / CONTENT 内容触达';
COMMENT ON COLUMN follow_task.content IS '跟进内容（上限 500）：AI 下发取 suggestedAction 话术';
COMMENT ON COLUMN follow_task.deadline IS '截止时间（KPI「今日到期/已逾期」判定基准）';
COMMENT ON COLUMN follow_task.status IS '状态三态：PENDING 待跟进 / DONE 已完成 / OVERDUE 已逾期（deadline 过期未 done 由查询侧推导或服务侧刷写）';
COMMENT ON COLUMN follow_task.priority IS '优先级三值：HIGH 高 / MEDIUM 中 / LOW 低';
COMMENT ON COLUMN follow_task.assignee IS '跟进人姓名：人工创建默认当前用户；AI 下发默认「系统派发」';
COMMENT ON COLUMN follow_task.completed_at IS '完成时刻（DONE 时落；KPI「本月完成」按本列聚合）';
COMMENT ON COLUMN follow_task.logs IS '跟进日志 JSONB 数组：[{by,text,at}]，创建/完成/转派/追加逐条 unshift';
COMMENT ON COLUMN follow_task.source IS '来源三值：MANUAL 人工创建 / CHURN AI 流失干预 / REPURCHASE AI 复购关怀';
COMMENT ON COLUMN follow_task.source_id IS '来源单据号（逻辑引用）：CHURN=churn_prediction.id / REPURCHASE=repurchase_prediction.id；MANUAL 为 NULL';
COMMENT ON COLUMN follow_task.idem_key IS 'AI 下发幂等键：source:sourceId:customerId:date（D8），部分唯一索引重放不重复建单；MANUAL 为 NULL 不参与';
COMMENT ON COLUMN follow_task.store_code IS '门店编码：NULL=全连锁，填值=客户归属门店（铁律-1-D）';
COMMENT ON COLUMN follow_task.created_by IS '创建人（人工创建=当前用户工号/姓名；AI 下发=SYSTEM）';
COMMENT ON COLUMN follow_task.created_at IS '创建时刻';
COMMENT ON COLUMN follow_task.updated_at IS '最近更新时刻（服务侧每次写操作刷 now()）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_follow_task_idem_key
    ON follow_task (idem_key) WHERE idem_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_follow_task_status   ON follow_task (status);
CREATE INDEX IF NOT EXISTS idx_follow_task_customer ON follow_task (customer_id);
CREATE INDEX IF NOT EXISTS idx_follow_task_deadline ON follow_task (deadline);
CREATE INDEX IF NOT EXISTS idx_follow_task_assignee ON follow_task (assignee);
