-- ============================================================
-- V81：T4 AI 中台底座·监控告警（T4-卡4）
-- 版本链：V80 后接续；meiyun_core / meiyun_seed 双库共享 flyway_schema_history。
-- 表前缀 ai_t4_（防撞登记：ai_model=V15 A1 模型上架 / ai_quota=V17 / ai_alert_rule=V17 A1 告警；
--   ai_t4_model(+_version)=V78 卡1；ai_t4_gpu_node(+_quota)=V79 卡2；ai_t4_feature(+_lineage)=V80 卡3）。
--   ai_t4_alert_rule   告警规则表（uk(code)/uk(name)；metric/operator/severity 三 chk；
--                      notify_channels JSONB 数组；code rule-* 业务码为前端主键锚）
--   ai_t4_alert_event  告警事件表（uk(code)；fk rule_code→ai_t4_alert_rule(code)；
--                      status chk FIRING/ACKNOWLEDGED/RESOLVED；code evt-*）
--   ai_t4_model_metric 模型实时指标快照表（uk(model_code) 一对一快照；mdl-* 引用卡1 模型 code）
-- 种子：5 指标 + 6 规则 + 5 事件逐字照前端 mock（t4Monitor.ts L156-209）；
--   mock 时基为相对 Date.now() 漂移（minsAgo），迁移改固定基准 T0=2026-09-29 23:00:00+08
--   （对齐开工拍 23:03 取整，防种子随部署时刻漂移）；
--   卡1 code 五引用 mdl-churn/mdl-skin/mdl-sales/mdl-rec/mdl-nlp 与 V78 一致，禁改名。
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_t4_alert_rule (
    rule_id         BIGSERIAL      PRIMARY KEY,
    code            VARCHAR(40)    NOT NULL,
    name            VARCHAR(64)    NOT NULL,
    model_code      VARCHAR(40)    NOT NULL,
    model_name      VARCHAR(64)    NOT NULL,
    metric          VARCHAR(16)    NOT NULL,
    threshold       NUMERIC(14, 4) NOT NULL,
    operator        VARCHAR(2)     NOT NULL,
    severity        VARCHAR(16)    NOT NULL,
    enabled         BOOLEAN        NOT NULL DEFAULT TRUE,
    notify_channels JSONB          NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_t4_alert_rule_code UNIQUE (code),
    CONSTRAINT uk_ai_t4_alert_rule_name UNIQUE (name),
    CONSTRAINT chk_ai_t4_alert_rule_metric CHECK (metric IN ('DRIFT','LATENCY','ERROR_RATE','ACCURACY','QPS_DROP')),
    CONSTRAINT chk_ai_t4_alert_rule_operator CHECK (operator IN ('>','<','>=','<=')),
    CONSTRAINT chk_ai_t4_alert_rule_severity CHECK (severity IN ('CRITICAL','WARNING','INFO'))
);

COMMENT ON TABLE  ai_t4_alert_rule IS 'T4 监控告警·告警规则表（/ai/monitor 规则 CRUD＋启停）';
COMMENT ON COLUMN ai_t4_alert_rule.code IS '业务码（rule-*，前端主键锚 id；种子为 rule-{语义} 可读锚，新建为 rule-时间戳36进制）';
COMMENT ON COLUMN ai_t4_alert_rule.name IS '规则名（全局唯一，如 皮肤影像-漂移过高）';
COMMENT ON COLUMN ai_t4_alert_rule.model_code IS '监控对象模型 code（引用卡1 ai_t4_model.code mdl-*，禁改名）';
COMMENT ON COLUMN ai_t4_alert_rule.model_name IS '模型显示名（冗余快照，随规则创建时登记）';
COMMENT ON COLUMN ai_t4_alert_rule.metric IS '监控指标：DRIFT 漂移分数/LATENCY P99 延迟/ERROR_RATE 错误率/ACCURACY 准确率/QPS_DROP QPS 下跌';
COMMENT ON COLUMN ai_t4_alert_rule.threshold IS '阈值';
COMMENT ON COLUMN ai_t4_alert_rule.operator IS '比较符：> / < / >= / <=';
COMMENT ON COLUMN ai_t4_alert_rule.severity IS '级别：CRITICAL 严重/WARNING 警告/INFO 提示';
COMMENT ON COLUMN ai_t4_alert_rule.enabled IS '启用开关（停用不评估）';
COMMENT ON COLUMN ai_t4_alert_rule.notify_channels IS '通知渠道 JSONB 数组（企微/邮件/短信）';
CREATE INDEX IF NOT EXISTS idx_ai_t4_alert_rule_enabled ON ai_t4_alert_rule (enabled);

CREATE TABLE IF NOT EXISTS ai_t4_alert_event (
    event_id        BIGSERIAL      PRIMARY KEY,
    code            VARCHAR(40)    NOT NULL,
    rule_code       VARCHAR(40)    NOT NULL,
    rule_name       VARCHAR(64)    NOT NULL,
    model_code      VARCHAR(40)    NOT NULL,
    model_name      VARCHAR(64)    NOT NULL,
    severity        VARCHAR(16)    NOT NULL,
    status          VARCHAR(16)    NOT NULL DEFAULT 'FIRING',
    message         VARCHAR(500)   NOT NULL DEFAULT '',
    value           NUMERIC(14, 4) NOT NULL,
    threshold       NUMERIC(14, 4) NOT NULL,
    triggered_at    TIMESTAMPTZ    NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ,
    acknowledged_by VARCHAR(64),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_t4_alert_event_code UNIQUE (code),
    CONSTRAINT fk_ai_t4_alert_event_rule FOREIGN KEY (rule_code) REFERENCES ai_t4_alert_rule (code),
    CONSTRAINT chk_ai_t4_alert_event_severity CHECK (severity IN ('CRITICAL','WARNING','INFO')),
    CONSTRAINT chk_ai_t4_alert_event_status CHECK (status IN ('FIRING','ACKNOWLEDGED','RESOLVED'))
);

COMMENT ON TABLE  ai_t4_alert_event IS 'T4 监控告警·告警事件表（触发→确认→解决闭环；追加触发＋状态迁移）';
COMMENT ON COLUMN ai_t4_alert_event.code IS '业务码（evt-*，前端主键锚 id；种子为 evt-{语义} 可读锚）';
COMMENT ON COLUMN ai_t4_alert_event.rule_code IS '触发规则 code（fk→ai_t4_alert_rule.code）';
COMMENT ON COLUMN ai_t4_alert_event.rule_name IS '规则名（冗余快照）';
COMMENT ON COLUMN ai_t4_alert_event.status IS '状态机：FIRING 告警中→ACKNOWLEDGED 已确认→RESOLVED 已解决（未确认可直接解决并补登 ack 字段，逐字 mock）';
COMMENT ON COLUMN ai_t4_alert_event.value IS '触发时实际值';
COMMENT ON COLUMN ai_t4_alert_event.threshold IS '触发时阈值快照';
COMMENT ON COLUMN ai_t4_alert_event.acknowledged_by IS '确认人（操作员显示名）';
CREATE INDEX IF NOT EXISTS idx_ai_t4_alert_event_status ON ai_t4_alert_event (status);
CREATE INDEX IF NOT EXISTS idx_ai_t4_alert_event_rule ON ai_t4_alert_event (rule_code);

CREATE TABLE IF NOT EXISTS ai_t4_model_metric (
    metric_id   BIGSERIAL      PRIMARY KEY,
    model_code  VARCHAR(40)    NOT NULL,
    model_name  VARCHAR(64)    NOT NULL,
    qps         INTEGER        NOT NULL DEFAULT 0,
    latency_p99 INTEGER        NOT NULL DEFAULT 0,
    error_rate  NUMERIC(10, 4) NOT NULL DEFAULT 0,
    drift_score NUMERIC(10, 4) NOT NULL DEFAULT 0,
    accuracy    NUMERIC(10, 4) NOT NULL DEFAULT 0,
    snapshot_at TIMESTAMPTZ    NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_t4_model_metric_model UNIQUE (model_code)
);

COMMENT ON TABLE  ai_t4_model_metric IS 'T4 监控告警·模型实时指标快照（/ai/monitor 模型健康 Tab；uk(model_code) 一对一最新快照）';
COMMENT ON COLUMN ai_t4_model_metric.model_code IS '模型 code（引用卡1 ai_t4_model.code mdl-*，禁改名；前端 modelId）';
COMMENT ON COLUMN ai_t4_model_metric.qps IS '每秒请求数（快照）';
COMMENT ON COLUMN ai_t4_model_metric.latency_p99 IS 'P99 延迟 ms（快照）';
COMMENT ON COLUMN ai_t4_model_metric.error_rate IS '错误率 %（快照）';
COMMENT ON COLUMN ai_t4_model_metric.drift_score IS '漂移分数 0-1（快照）';
COMMENT ON COLUMN ai_t4_model_metric.accuracy IS '准确率 0-1（快照）';
COMMENT ON COLUMN ai_t4_model_metric.snapshot_at IS '快照采集时刻（前端 timestamp）';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_ai_t4_alert_rule_updated_at') THEN
        CREATE TRIGGER trg_ai_t4_alert_rule_updated_at BEFORE UPDATE ON ai_t4_alert_rule
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END $$;

-- ---------- 种子：5 模型指标快照（逐字 mock L156-162；snapshot_at=T0-minsAgo(1/2/3) 固定基准） ----------
INSERT INTO ai_t4_model_metric (model_code, model_name, qps, latency_p99, error_rate, drift_score, accuracy, snapshot_at)
VALUES
  ('mdl-churn', '客户流失预测 v3', 142, 58, 0.18, 0.21, 0.91, '2026-09-29 22:59:00+08'::timestamptz),
  ('mdl-skin', '皮肤影像分类', 28, 218, 0.62, 0.78, 0.86, '2026-09-29 22:59:00+08'::timestamptz),
  ('mdl-sales', '门店销量预测', 12, 92, 0.05, 0.12, 0.92, '2026-09-29 22:58:00+08'::timestamptz),
  ('mdl-rec', '项目疗程推荐', 86, 76, 0.02, 0.08, 0.88, '2026-09-29 22:59:00+08'::timestamptz),
  ('mdl-nlp', '客服意图识别(待发布)', 0, 0, 0, 0, 0.93, '2026-09-29 22:57:00+08'::timestamptz)
ON CONFLICT (model_code) DO NOTHING;

-- ---------- 种子：6 告警规则（逐字 mock L164-171；created_at/updated_at=T0-minsAgo(10d/8d/6d/3d/2d)） ----------
INSERT INTO ai_t4_alert_rule (code, name, model_code, model_name, metric, threshold, operator, severity,
                              enabled, notify_channels, created_at, updated_at)
VALUES
  ('rule-skin-drift', '皮肤影像-漂移过高', 'mdl-skin', '皮肤影像分类', 'DRIFT', 0.7, '>', 'CRITICAL', TRUE, '["企微","邮件"]'::jsonb, '2026-09-19 23:00:00+08'::timestamptz, '2026-09-19 23:00:00+08'::timestamptz),
  ('rule-churn-latency', '流失预测-P99 延迟', 'mdl-churn', '客户流失预测 v3', 'LATENCY', 200, '>', 'WARNING', TRUE, '["企微"]'::jsonb, '2026-09-21 23:00:00+08'::timestamptz, '2026-09-21 23:00:00+08'::timestamptz),
  ('rule-skin-error', '皮肤影像-错误率', 'mdl-skin', '皮肤影像分类', 'ERROR_RATE', 5, '>=', 'WARNING', TRUE, '["企微","邮件","短信"]'::jsonb, '2026-09-21 23:00:00+08'::timestamptz, '2026-09-21 23:00:00+08'::timestamptz),
  ('rule-sales-accuracy', '销量预测-准确率下跌', 'mdl-sales', '门店销量预测', 'ACCURACY', 0.8, '<', 'WARNING', TRUE, '["邮件"]'::jsonb, '2026-09-23 23:00:00+08'::timestamptz, '2026-09-23 23:00:00+08'::timestamptz),
  ('rule-rec-qps', '推荐服务-QPS 下跌', 'mdl-rec', '项目疗程推荐', 'QPS_DROP', 30, '>', 'INFO', FALSE, '["企微"]'::jsonb, '2026-09-26 23:00:00+08'::timestamptz, '2026-09-26 23:00:00+08'::timestamptz),
  ('rule-churn-drift', '流失预测-漂移', 'mdl-churn', '客户流失预测 v3', 'DRIFT', 0.6, '>', 'INFO', TRUE, '["企微"]'::jsonb, '2026-09-27 23:00:00+08'::timestamptz, '2026-09-27 23:00:00+08'::timestamptz)
ON CONFLICT (code) DO NOTHING;

-- ---------- 种子：5 告警事件（逐字 mock L173-209；FIRING×2/ACKNOWLEDGED×1/RESOLVED×2；时基固定 T0 派生） ----------
INSERT INTO ai_t4_alert_event (code, rule_code, rule_name, model_code, model_name, severity, status,
                               message, value, threshold, triggered_at, acknowledged_at, resolved_at, acknowledged_by)
VALUES
  ('evt-skin-drift-1', 'rule-skin-drift', '皮肤影像-漂移过高', 'mdl-skin', '皮肤影像分类', 'CRITICAL', 'FIRING',
   '漂移分数 0.78 超过阈值 0.70，建议立即复核模型效果或触发重训', 0.78, 0.7,
   '2026-09-29 22:42:00+08'::timestamptz, NULL, NULL, NULL),
  ('evt-skin-error-1', 'rule-skin-error', '皮肤影像-错误率', 'mdl-skin', '皮肤影像分类', 'WARNING', 'FIRING',
   '近 5 分钟错误率 5.6% ≥ 阈值 5%，疑似推理服务异常', 5.6, 5,
   '2026-09-29 22:34:00+08'::timestamptz, NULL, NULL, NULL),
  ('evt-churn-latency-1', 'rule-churn-latency', '流失预测-P99 延迟', 'mdl-churn', '客户流失预测 v3', 'WARNING', 'ACKNOWLEDGED',
   'P99 延迟 218ms 超过阈值 200ms，已通知值班同学', 218, 200,
   '2026-09-29 21:00:00+08'::timestamptz, '2026-09-29 21:05:00+08'::timestamptz, NULL, '王运维'),
  ('evt-sales-accuracy-1', 'rule-sales-accuracy', '销量预测-准确率下跌', 'mdl-sales', '门店销量预测', 'WARNING', 'RESOLVED',
   '准确率跌至 0.78，低于阈值 0.80', 0.78, 0.8,
   '2026-09-29 15:00:00+08'::timestamptz, '2026-09-29 15:03:00+08'::timestamptz, '2026-09-29 17:00:00+08'::timestamptz, '赵磊'),
  ('evt-skin-drift-0', 'rule-skin-drift', '皮肤影像-漂移过高', 'mdl-skin', '皮肤影像分类', 'CRITICAL', 'RESOLVED',
   '历史漂移告警（0.72）已随 v1.4 重训解决', 0.72, 0.7,
   '2026-09-27 23:00:00+08'::timestamptz, '2026-09-27 23:05:00+08'::timestamptz, '2026-09-28 23:00:00+08'::timestamptz, '张医生')
ON CONFLICT (code) DO NOTHING;
