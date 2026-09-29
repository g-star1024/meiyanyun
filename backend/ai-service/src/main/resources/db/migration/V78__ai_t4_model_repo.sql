-- ============================================================
-- V78：T4 AI 中台底座·模型仓库（T4-卡1）
-- 版本链：V77 后接续；meiyun_core / meiyun_seed 双库共享 flyway_schema_history。
-- 表前缀 ai_t4_（三撞回避：ai_model=V15 A1 模型上架 / ai_quota=V17 / ai_alert_rule=V17）。
--   ai_t4_model          模型登记主表（业务码 code 为前端主键锚，与 mock 字符串 id 逐字一致）
--   ai_t4_model_version  模型版本表（uk(model_id, version)；metrics 存 JSON 文本，与前端 Record<string,number> 对应）
-- 种子：6 模型 + 9 版本逐字照前端 mock（t4Model.ts），时间用 now()-interval 保持相对新鲜；
--   种子 code mdl-churn/mdl-skin/mdl-nlp/mdl-rec/mdl-sales/mdl-copy 被卡4 监控种子引用，禁止改名。
-- 审批中心联动：ai_approval.approval_type 无 CHECK 约束（V15 VARCHAR(32)），
--   T4_MODEL 类型直接可写；此处仅追加 COMMENT 如实登记（铁律 1 只加不改）。
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_t4_model (
    model_id        BIGSERIAL         PRIMARY KEY,
    code            VARCHAR(40)       NOT NULL,
    name            VARCHAR(120)      NOT NULL,
    type            VARCHAR(20)       NOT NULL,
    description     VARCHAR(500)      NOT NULL DEFAULT '',
    owner           VARCHAR(64)       NOT NULL,
    department      VARCHAR(64)       NOT NULL,
    tags            VARCHAR(500),
    status          VARCHAR(20)       NOT NULL DEFAULT 'DRAFT',
    current_version VARCHAR(40),
    input_schema    TEXT              NOT NULL DEFAULT '{}',
    output_schema   TEXT              NOT NULL DEFAULT '{}',
    call_count_30d  BIGINT            NOT NULL DEFAULT 0,
    avg_latency_ms  INTEGER           NOT NULL DEFAULT 0,
    error_rate      DOUBLE PRECISION  NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ       NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_t4_model_code UNIQUE (code),
    CONSTRAINT chk_ai_t4_model_type CHECK (type IN ('CLASSIFICATION','REGRESSION','NLP','CV','RECOMMEND','GENERATIVE')),
    CONSTRAINT chk_ai_t4_model_status CHECK (status IN ('DRAFT','TRAINING','READY','PUBLISHED','DEPRECATED'))
);

COMMENT ON TABLE  ai_t4_model IS 'T4 模型仓库·模型主表（/ai/models 模型管理 Tab）';
COMMENT ON COLUMN ai_t4_model.code IS '业务码（mdl-*，前端主键锚；卡4 监控跨表引用）';
COMMENT ON COLUMN ai_t4_model.type IS '类型：CLASSIFICATION/REGRESSION/NLP/CV/RECOMMEND/GENERATIVE';
COMMENT ON COLUMN ai_t4_model.status IS '状态：DRAFT/TRAINING/READY/PUBLISHED/DEPRECATED';
COMMENT ON COLUMN ai_t4_model.tags IS '逗号分隔标签串（前端 string[] 适配层互转）';
COMMENT ON COLUMN ai_t4_model.error_rate IS '近30天错误率（%）';
CREATE INDEX IF NOT EXISTS idx_ai_t4_model_status ON ai_t4_model (status);

CREATE TABLE IF NOT EXISTS ai_t4_model_version (
    version_id   BIGSERIAL    PRIMARY KEY,
    model_id     BIGINT       NOT NULL REFERENCES ai_t4_model (model_id) ON DELETE CASCADE,
    version      VARCHAR(40)  NOT NULL,
    metrics      TEXT         NOT NULL DEFAULT '{}',
    status       VARCHAR(20)  NOT NULL DEFAULT 'TRAINING',
    trained_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    approved_by  VARCHAR(64),
    remark       VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_t4_model_version UNIQUE (model_id, version),
    CONSTRAINT chk_ai_t4_model_version_status CHECK (status IN ('DRAFT','TRAINING','READY','PUBLISHED','DEPRECATED'))
);

COMMENT ON TABLE  ai_t4_model_version IS 'T4 模型仓库·版本表；发布仅经审批中心 T4_MODEL 单联动（红线：非 READY 禁发）';
COMMENT ON COLUMN ai_t4_model_version.metrics IS '训练指标 JSON 文本（如 {"auc":0.89}，与前端 Record<string,number> 对应）';
COMMENT ON COLUMN ai_t4_model_version.approved_by IS '发布审批人（审批中心 decide 联动写入；回滚写操作人）';
CREATE INDEX IF NOT EXISTS idx_ai_t4_model_version_model ON ai_t4_model_version (model_id);
CREATE INDEX IF NOT EXISTS idx_ai_t4_model_version_status ON ai_t4_model_version (status);

COMMENT ON COLUMN ai_approval.approval_type IS '类型：PROVIDER/MODEL/BINDING/T4_MODEL（T4 模型发布申请，V78 追加）';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_ai_t4_model_updated_at') THEN
        CREATE TRIGGER trg_ai_t4_model_updated_at BEFORE UPDATE ON ai_t4_model
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END $$;

-- ---------- 种子：6 模型（逐字 mock；ON CONFLICT 幂等） ----------
INSERT INTO ai_t4_model (code, name, type, description, owner, department, tags, status,
                         current_version, input_schema, output_schema,
                         call_count_30d, avg_latency_ms, error_rate, created_at, updated_at)
VALUES
  ('mdl-churn', '客户流失预测 v3', 'CLASSIFICATION',
   '基于近 90 天到店/消费/互动行为预测 30 天内流失概率，输出高/中/低三档。',
   '李明', '数据智能部', '客户运营,流失预警,XGBoost', 'PUBLISHED', '3.2.0',
   '{
  "customer_id": "string",
  "features": { "recency_days": "int", "frequency_90d": "int", "monetary_90d": "float" }
}',
   '{
  "churn_prob": "float 0-1",
  "level": "HIGH|MEDIUM|LOW",
  "top_factors": "string[]"
}',
   186420, 42, 0.18, now() - interval '200 days', now() - interval '18 days'),
  ('mdl-skin', '皮肤问题影像分类', 'CV',
   '对面诊皮肤影像自动归类（痤疮/色斑/敏感/老化/正常），辅助医师初筛。',
   '张医生', '皮肤科', 'CV,医疗影像,ResNet', 'PUBLISHED', '1.5.0',
   '{
  "image_base64": "string",
  "resolution": "string"
}',
   '{
  "label": "string",
  "confidence": "float",
  "candidates": "{label,confidence}[]"
}',
   42180, 186, 0.62, now() - interval '100 days', now() - interval '10 days'),
  ('mdl-nlp', '智能客服意图识别', 'NLP',
   '识别企微/小程序客户咨询意图（预约/退款/投诉/咨询/复购），路由到对应坐席组。',
   '陈晓', '客户体验部', 'NLP,BERT,客服', 'READY', NULL,
   '{
  "text": "string",
  "channel": "WECHAT|APP|WEB"
}',
   '{
  "intent": "string",
  "confidence": "float",
  "slots": "Record<string,string>"
}',
   0, 0, 0, now() - interval '30 days', now() - interval '3 days'),
  ('mdl-rec', '项目疗程推荐', 'RECOMMEND',
   '基于客户画像 + 历史消费，推荐 Top3 高转化项目疗程，支撑导购侧栏。',
   '王悦', '营销中心', '推荐,DeepFM,营销', 'TRAINING', NULL,
   '{
  "customer_id": "string",
  "context": { "channel": "string", "store_id": "string" }
}',
   '{
  "items": "{item_id,score,reason}[]"
}',
   0, 0, 0, now() - interval '45 days', now() - interval '1 days'),
  ('mdl-sales', '门店销量预测', 'REGRESSION',
   '按门店/SKU 预测未来 7/14/30 天销量，驱动采购与排班。',
   '赵磊', '数据智能部', '时序,Prophet,供应链', 'PUBLISHED', '1.2.0',
   '{
  "store_id": "string",
  "sku_id": "string",
  "horizon_days": "int"
}',
   '{
  "dates": "string[]",
  "yhat": "float[]",
  "yhat_lower": "float[]",
  "yhat_upper": "float[]"
}',
   9820, 68, 0.05, now() - interval '60 days', now() - interval '12 days'),
  ('mdl-copy', '营销文案生成', 'GENERATIVE',
   '基于活动主题/客户分群生成朋友圈/企微/短信多版本文案（合规词过滤）。',
   '孙琳', '营销中心', 'LLM,AIGC,营销', 'DRAFT', NULL,
   '{
  "topic": "string",
  "segment": "string",
  "channel": "WECHAT|MOMENTS|SMS"
}',
   '{
  "variants": "string[]",
  "compliance_passed": "bool"
}',
   0, 0, 0, now() - interval '5 days', now() - interval '5 days')
ON CONFLICT (code) DO NOTHING;

-- ---------- 种子：8 版本（逐字 mock；NOT EXISTS 幂等） ----------
INSERT INTO ai_t4_model_version (model_id, version, metrics, status, trained_at, published_at, approved_by, remark)
SELECT m.model_id, x.version, x.metrics, x.status, x.trained_at::timestamptz, x.published_at::timestamptz, x.approved_by, x.remark
FROM ai_t4_model m
JOIN (VALUES
  ('1.0.0', '{"auc":0.78,"accuracy":0.82}', 'DEPRECATED',
   now() - interval '180 days', now() - interval '170 days', '王审批', '初版'),
  ('2.0.0', '{"auc":0.83,"accuracy":0.85}', 'DEPRECATED',
   now() - interval '120 days', now() - interval '110 days', '王审批', NULL),
  ('3.2.0', '{"auc":0.89,"accuracy":0.91,"f1":0.87}', 'PUBLISHED',
   now() - interval '20 days', now() - interval '18 days', 'T3-01 审批人', NULL)
) AS x(version, metrics, status, trained_at, published_at, approved_by, remark) ON true
WHERE m.code = 'mdl-churn'
  AND NOT EXISTS (SELECT 1 FROM ai_t4_model_version v WHERE v.model_id = m.model_id AND v.version = x.version);

INSERT INTO ai_t4_model_version (model_id, version, metrics, status, trained_at, published_at, approved_by, remark)
SELECT m.model_id, x.version, x.metrics, x.status, x.trained_at::timestamptz, x.published_at::timestamptz, x.approved_by, x.remark
FROM ai_t4_model m
JOIN (VALUES
  ('1.0.0', '{"top1":0.81,"top3":0.94}', 'DEPRECATED',
   now() - interval '90 days', now() - interval '80 days', '医务部', NULL),
  ('1.5.0', '{"top1":0.88,"top3":0.97}', 'PUBLISHED',
   now() - interval '12 days', now() - interval '10 days', 'T3-01 审批人', NULL)
) AS x(version, metrics, status, trained_at, published_at, approved_by, remark) ON true
WHERE m.code = 'mdl-skin'
  AND NOT EXISTS (SELECT 1 FROM ai_t4_model_version v WHERE v.model_id = m.model_id AND v.version = x.version);

INSERT INTO ai_t4_model_version (model_id, version, metrics, status, trained_at, published_at, approved_by, remark)
SELECT m.model_id, x.version, x.metrics, x.status, x.trained_at::timestamptz, x.published_at::timestamptz, x.approved_by, x.remark
FROM ai_t4_model m
JOIN (VALUES
  ('0.9.0', '{"f1":0.91,"accuracy":0.93}', 'READY',
   now() - interval '3 days', NULL, NULL, '待 T3-01 审批发布')
) AS x(version, metrics, status, trained_at, published_at, approved_by, remark) ON true
WHERE m.code = 'mdl-nlp'
  AND NOT EXISTS (SELECT 1 FROM ai_t4_model_version v WHERE v.model_id = m.model_id AND v.version = x.version);

INSERT INTO ai_t4_model_version (model_id, version, metrics, status, trained_at, published_at, approved_by, remark)
SELECT m.model_id, x.version, x.metrics, x.status, x.trained_at::timestamptz, x.published_at::timestamptz, x.approved_by, x.remark
FROM ai_t4_model m
JOIN (VALUES
  ('2.1.0', '{}', 'TRAINING',
   now() - interval '1 days', NULL, NULL, '训练中，预计 4h 后完成')
) AS x(version, metrics, status, trained_at, published_at, approved_by, remark) ON true
WHERE m.code = 'mdl-rec'
  AND NOT EXISTS (SELECT 1 FROM ai_t4_model_version v WHERE v.model_id = m.model_id AND v.version = x.version);

INSERT INTO ai_t4_model_version (model_id, version, metrics, status, trained_at, published_at, approved_by, remark)
SELECT m.model_id, x.version, x.metrics, x.status, x.trained_at::timestamptz, x.published_at::timestamptz, x.approved_by, x.remark
FROM ai_t4_model m
JOIN (VALUES
  ('1.2.0', '{"mape":0.084,"rmse":12.4}', 'PUBLISHED',
   now() - interval '14 days', now() - interval '12 days', 'T3-01 审批人', NULL)
) AS x(version, metrics, status, trained_at, published_at, approved_by, remark) ON true
WHERE m.code = 'mdl-sales'
  AND NOT EXISTS (SELECT 1 FROM ai_t4_model_version v WHERE v.model_id = m.model_id AND v.version = x.version);
