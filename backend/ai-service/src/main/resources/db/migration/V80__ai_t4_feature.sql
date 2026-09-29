-- ============================================================
-- V80：T4 AI 中台底座·特征平台（T4-卡3）
-- 版本链：V79 后接续；meiyun_core / meiyun_seed 双库共享 flyway_schema_history。
-- 表前缀 ai_t4_（防撞登记：ai_model=V15 A1 模型上架 / ai_quota=V17 / ai_alert_rule=V17；
--   ai_t4_model(+_version)=V78 卡1；ai_t4_gpu_node(+_quota)=V79 卡2）。
--   ai_t4_feature         特征表（uk(name)；code feat-* 业务码为前端主键锚）
--   ai_t4_feature_lineage 血缘表（kind=NODE/EDGE 单表图存储；节点/边部分唯一索引幂等）
-- 种子：14 特征 + 25 血缘节点 + 20 血缘边逐字照前端 mock（t4Feature.ts L170-250）；
--   created_at=now()-60d / updated_at=now()-2d 对齐 mock daysAgo(60)/daysAgo(2)。
-- 血缘 FEATURE 节点 id 与 ai_t4_feature.code 对齐（feat-{name} 定长可读锚）；
--   MODEL 节点 code mdl-churn/mdl-skin/mdl-rec 与卡1 模型仓库一致，禁改名。
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_t4_feature (
    feature_id     BIGSERIAL      PRIMARY KEY,
    code           VARCHAR(40)    NOT NULL,
    name           VARCHAR(64)    NOT NULL,
    feature_group  VARCHAR(64)    NOT NULL,
    type           VARCHAR(16)    NOT NULL,
    value_type     VARCHAR(16)    NOT NULL,
    description    VARCHAR(500)   NOT NULL DEFAULT '',
    source         VARCHAR(64)    NOT NULL,
    status         VARCHAR(16)    NOT NULL DEFAULT 'DRAFT',
    owner          VARCHAR(64)    NOT NULL,
    online_serving BOOLEAN        NOT NULL DEFAULT FALSE,
    ttl            VARCHAR(32),
    call_count_30d BIGINT         NOT NULL DEFAULT 0,
    freshness      VARCHAR(32)    NOT NULL DEFAULT 'T+1',
    version        VARCHAR(16)    NOT NULL DEFAULT '1.0.0',
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_t4_feature_code UNIQUE (code),
    CONSTRAINT uk_ai_t4_feature_name UNIQUE (name),
    CONSTRAINT chk_ai_t4_feature_status CHECK (status IN ('DRAFT','REGISTERED','PUBLISHED','DEPRECATED')),
    CONSTRAINT chk_ai_t4_feature_type CHECK (type IN ('ONLINE','OFFLINE')),
    CONSTRAINT chk_ai_t4_feature_value_type CHECK (value_type IN ('INT','FLOAT','STRING','VECTOR','BOOL'))
);

COMMENT ON TABLE  ai_t4_feature IS 'T4 特征平台·特征表（/ai/features 注册/发布/下线/在线服务）';
COMMENT ON COLUMN ai_t4_feature.code IS '业务码（feat-*，前端主键锚；种子为 feat-{name} 可读锚，注册新码为 feat-时间戳36进制）';
COMMENT ON COLUMN ai_t4_feature.name IS '特征名（全局唯一，如 cust_recency_days）';
COMMENT ON COLUMN ai_t4_feature.feature_group IS '特征分组（前端 group 字段；group 为 SQL 保留字故列名加前缀）';
COMMENT ON COLUMN ai_t4_feature.type IS '特征类型：ONLINE/OFFLINE';
COMMENT ON COLUMN ai_t4_feature.value_type IS '值类型：INT/FLOAT/STRING/VECTOR/BOOL';
COMMENT ON COLUMN ai_t4_feature.source IS '数据源表（如 dwd_customer_visit；血缘 SOURCE 节点锚 src-{source}）';
COMMENT ON COLUMN ai_t4_feature.status IS '状态：DRAFT/REGISTERED/PUBLISHED/DEPRECATED';
COMMENT ON COLUMN ai_t4_feature.owner IS '负责人';
COMMENT ON COLUMN ai_t4_feature.online_serving IS '在线服务开关';
COMMENT ON COLUMN ai_t4_feature.ttl IS '特征有效期（可空，如 30天）';
COMMENT ON COLUMN ai_t4_feature.call_count_30d IS '近 30 天调用次数（种子快照，无真实采集）';
COMMENT ON COLUMN ai_t4_feature.freshness IS '新鲜度（如 T+0 小时级 / T+1 天级）';
COMMENT ON COLUMN ai_t4_feature.version IS '当前版本号（注册固定 1.0.0）';
CREATE INDEX IF NOT EXISTS idx_ai_t4_feature_status ON ai_t4_feature (status);

CREATE TABLE IF NOT EXISTS ai_t4_feature_lineage (
    lineage_id BIGSERIAL   PRIMARY KEY,
    kind       VARCHAR(8)  NOT NULL,
    node_id    VARCHAR(64),
    node_name  VARCHAR(120),
    node_type  VARCHAR(16),
    from_node  VARCHAR(64),
    to_node    VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_t4_feature_lineage_kind CHECK (kind IN ('NODE','EDGE')),
    CONSTRAINT chk_ai_t4_feature_lineage_node_type CHECK (node_type IS NULL OR node_type IN ('SOURCE','FEATURE','MODEL','SERVICE'))
);

COMMENT ON TABLE  ai_t4_feature_lineage IS 'T4 特征平台·血缘表（kind=NODE/EDGE 单表图存储；追加式，节点/边不更新）';
COMMENT ON COLUMN ai_t4_feature_lineage.kind IS '行类型：NODE=节点 / EDGE=边';
COMMENT ON COLUMN ai_t4_feature_lineage.node_id IS '节点 id（NODE 行；FEATURE 节点=ai_t4_feature.code，SOURCE=src-*，MODEL=mdl-*，SERVICE=svc-*）';
COMMENT ON COLUMN ai_t4_feature_lineage.node_name IS '节点显示名（NODE 行）';
COMMENT ON COLUMN ai_t4_feature_lineage.node_type IS '节点类型（NODE 行）：SOURCE/FEATURE/MODEL/SERVICE';
COMMENT ON COLUMN ai_t4_feature_lineage.from_node IS '上游节点 id（EDGE 行）';
COMMENT ON COLUMN ai_t4_feature_lineage.to_node IS '下游节点 id（EDGE 行）';
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_t4_feature_lineage_node ON ai_t4_feature_lineage (node_id) WHERE kind = 'NODE';
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_t4_feature_lineage_edge ON ai_t4_feature_lineage (from_node, to_node) WHERE kind = 'EDGE';
CREATE INDEX IF NOT EXISTS idx_ai_t4_feature_lineage_kind ON ai_t4_feature_lineage (kind, lineage_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_ai_t4_feature_updated_at') THEN
        CREATE TRIGGER trg_ai_t4_feature_updated_at BEFORE UPDATE ON ai_t4_feature
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END $$;

-- ---------- 种子：14 特征（逐字 mock L170-183；ON CONFLICT 幂等；created_at/updated_at 对齐 daysAgo(60)/daysAgo(2)） ----------
INSERT INTO ai_t4_feature (code, name, feature_group, type, value_type, description, source,
                           status, owner, online_serving, ttl, call_count_30d, freshness, version,
                           created_at, updated_at)
VALUES
  ('feat-cust_recency_days', 'cust_recency_days', '客户RFM', 'ONLINE', 'INT', '客户最近一次到店距今天数', 'dwd_customer_visit', 'PUBLISHED', '李明', TRUE, '30天', 186420, 'T+0 小时级', '2.1.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-cust_frequency_90d', 'cust_frequency_90d', '客户RFM', 'ONLINE', 'INT', '近 90 天消费/到店次数', 'dwd_customer_order', 'PUBLISHED', '李明', TRUE, '90天', 186420, 'T+0 小时级', '2.1.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-cust_monetary_90d', 'cust_monetary_90d', '客户RFM', 'ONLINE', 'FLOAT', '近 90 天累计消费金额', 'dwd_customer_order', 'PUBLISHED', '李明', TRUE, '90天', 182300, 'T+0 小时级', '2.1.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-skin_concern_embedding', 'skin_concern_embedding', '皮肤影像', 'ONLINE', 'VECTOR', '面诊皮肤问题向量（512 维）', 'dwd_skin_image', 'PUBLISHED', '张医生', TRUE, '180天', 42180, 'T+0 实时', '1.5.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-skin_acne_score', 'skin_acne_score', '皮肤影像', 'ONLINE', 'FLOAT', '痤疮严重度评分 0-1', 'dwd_skin_image', 'PUBLISHED', '张医生', TRUE, NULL, 40120, 'T+0 实时', '1.5.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-intent_embedding', 'intent_embedding', 'NLP特征', 'ONLINE', 'VECTOR', '客户对话意图向量（768 维）', 'dwd_chat_message', 'REGISTERED', '陈晓', FALSE, NULL, 0, 'T+1 天级', '0.9.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-intent_topic', 'intent_topic', 'NLP特征', 'OFFLINE', 'STRING', '咨询主题分类（预约/退款/投诉/咨询/复购）', 'dwd_chat_message', 'PUBLISHED', '陈晓', FALSE, NULL, 8200, 'T+1 天级', '1.2.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-item_popularity_score', 'item_popularity_score', '商品推荐', 'ONLINE', 'FLOAT', '项目热度分（近 30 天销量+点击+收藏加权）', 'dws_item_stats', 'PUBLISHED', '王悦', TRUE, '7天', 96500, 'T+0 小时级', '3.0.1', now() - interval '60 days', now() - interval '2 days'),
  ('feat-cust_segment_label', 'cust_segment_label', '客户画像', 'OFFLINE', 'STRING', '客户分群标签（高价值/潜力/沉睡/流失）', 'ads_customer_segment', 'PUBLISHED', '赵磊', FALSE, NULL, 124000, 'T+1 天级', '4.2.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-cust_ltv_pred', 'cust_ltv_pred', '客户画像', 'OFFLINE', 'FLOAT', '未来 12 个月预测 LTV', 'ads_customer_ltv', 'REGISTERED', '赵磊', FALSE, NULL, 0, 'T+7 周级', '0.3.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-store_daily_sales', 'store_daily_sales', '门店运营', 'OFFLINE', 'FLOAT', '门店日销售额', 'dws_store_daily', 'PUBLISHED', '赵磊', FALSE, NULL, 9820, 'T+1 天级', '1.2.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-coupon_sensitivity', 'coupon_sensitivity', '营销偏好', 'ONLINE', 'FLOAT', '客户对优惠券的敏感度 0-1', 'dwd_coupon_usage', 'DRAFT', '孙琳', FALSE, NULL, 0, 'T+1 天级', '0.1.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-is_vip_customer', 'is_vip_customer', '客户画像', 'ONLINE', 'BOOL', '是否 VIP 客户', 'dim_customer', 'PUBLISHED', '李明', TRUE, NULL, 210000, 'T+0 实时', '1.0.0', now() - interval '60 days', now() - interval '2 days'),
  ('feat-visit_channel_prefer', 'visit_channel_prefer', '营销偏好', 'OFFLINE', 'STRING', '到访渠道偏好（已被新标签替代）', 'dws_channel_stats', 'DEPRECATED', '孙琳', FALSE, NULL, 0, 'T+7', '1.0.0', now() - interval '60 days', now() - interval '2 days')
ON CONFLICT (code) DO NOTHING;

-- ---------- 种子：25 血缘节点（逐字 mock L208-228；6 SOURCE + 14 FEATURE + 3 MODEL + 2 SERVICE） ----------
INSERT INTO ai_t4_feature_lineage (kind, node_id, node_name, node_type)
VALUES
  ('NODE', 'src-visit', 'dwd_customer_visit', 'SOURCE'),
  ('NODE', 'src-order', 'dwd_customer_order', 'SOURCE'),
  ('NODE', 'src-image', 'dwd_skin_image', 'SOURCE'),
  ('NODE', 'src-chat', 'dwd_chat_message', 'SOURCE'),
  ('NODE', 'src-item', 'dws_item_stats', 'SOURCE'),
  ('NODE', 'src-seg', 'ads_customer_segment', 'SOURCE'),
  ('NODE', 'feat-cust_recency_days', 'cust_recency_days', 'FEATURE'),
  ('NODE', 'feat-cust_frequency_90d', 'cust_frequency_90d', 'FEATURE'),
  ('NODE', 'feat-cust_monetary_90d', 'cust_monetary_90d', 'FEATURE'),
  ('NODE', 'feat-skin_concern_embedding', 'skin_concern_embedding', 'FEATURE'),
  ('NODE', 'feat-skin_acne_score', 'skin_acne_score', 'FEATURE'),
  ('NODE', 'feat-intent_embedding', 'intent_embedding', 'FEATURE'),
  ('NODE', 'feat-intent_topic', 'intent_topic', 'FEATURE'),
  ('NODE', 'feat-item_popularity_score', 'item_popularity_score', 'FEATURE'),
  ('NODE', 'feat-cust_segment_label', 'cust_segment_label', 'FEATURE'),
  ('NODE', 'feat-cust_ltv_pred', 'cust_ltv_pred', 'FEATURE'),
  ('NODE', 'feat-store_daily_sales', 'store_daily_sales', 'FEATURE'),
  ('NODE', 'feat-coupon_sensitivity', 'coupon_sensitivity', 'FEATURE'),
  ('NODE', 'feat-is_vip_customer', 'is_vip_customer', 'FEATURE'),
  ('NODE', 'feat-visit_channel_prefer', 'visit_channel_prefer', 'FEATURE'),
  ('NODE', 'mdl-churn', '客户流失预测 v3', 'MODEL'),
  ('NODE', 'mdl-skin', '皮肤影像分类', 'MODEL'),
  ('NODE', 'mdl-rec', '疗程推荐 DeepFM', 'MODEL'),
  ('NODE', 'svc-guide', '导购侧栏推荐服务', 'SERVICE'),
  ('NODE', 'svc-alert', '流失预警推送服务', 'SERVICE')
ON CONFLICT (node_id) WHERE kind = 'NODE' DO NOTHING;

-- ---------- 种子：20 血缘边（逐字 mock L229-250；9 源→特征 + 8 特征→模型 + 3 模型→服务） ----------
INSERT INTO ai_t4_feature_lineage (kind, from_node, to_node)
VALUES
  ('EDGE', 'src-visit', 'feat-cust_recency_days'),
  ('EDGE', 'src-order', 'feat-cust_frequency_90d'),
  ('EDGE', 'src-order', 'feat-cust_monetary_90d'),
  ('EDGE', 'src-image', 'feat-skin_concern_embedding'),
  ('EDGE', 'src-image', 'feat-skin_acne_score'),
  ('EDGE', 'src-chat', 'feat-intent_embedding'),
  ('EDGE', 'src-chat', 'feat-intent_topic'),
  ('EDGE', 'src-item', 'feat-item_popularity_score'),
  ('EDGE', 'src-seg', 'feat-cust_segment_label'),
  ('EDGE', 'feat-cust_recency_days', 'mdl-churn'),
  ('EDGE', 'feat-cust_frequency_90d', 'mdl-churn'),
  ('EDGE', 'feat-cust_monetary_90d', 'mdl-churn'),
  ('EDGE', 'feat-cust_segment_label', 'mdl-churn'),
  ('EDGE', 'feat-skin_concern_embedding', 'mdl-skin'),
  ('EDGE', 'feat-skin_acne_score', 'mdl-skin'),
  ('EDGE', 'feat-item_popularity_score', 'mdl-rec'),
  ('EDGE', 'feat-cust_monetary_90d', 'mdl-rec'),
  ('EDGE', 'mdl-churn', 'svc-alert'),
  ('EDGE', 'mdl-rec', 'svc-guide'),
  ('EDGE', 'mdl-skin', 'svc-guide')
ON CONFLICT (from_node, to_node) WHERE kind = 'EDGE' DO NOTHING;
