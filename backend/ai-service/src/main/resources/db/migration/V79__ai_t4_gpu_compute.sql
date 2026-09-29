-- ============================================================
-- V79：T4 AI 中台底座·算力管理（T4-卡2）
-- 版本链：V78 后接续；meiyun_core / meiyun_seed 双库共享 flyway_schema_history。
-- 表前缀 ai_t4_（防撞登记：ai_model=V15 A1 模型上架 / ai_quota=V17 / ai_alert_rule=V17；
--   ai_t4_model(+_version)=V78 卡1）。
--   ai_t4_gpu_node   GPU 节点表（业务码 code 为前端主键锚，与 name 同值：gpu-a100-01 等天然稳定）
--   ai_t4_gpu_quota  部门配额表（uk(department, project, period)；code quota-* 可读缩写稳定锚）
-- 种子：12 GPU + 6 配额逐字照前端 mock（t4Compute.ts）；mock 无时间字段，created_at/updated_at 全走默认 now()。
-- 纯前端保留不入库：GPU_MODEL_PRICE 型号单价表 / simulateCost 成本模拟器（契约⑥）。
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_t4_gpu_node (
    node_id      BIGSERIAL      PRIMARY KEY,
    code         VARCHAR(40)    NOT NULL,
    name         VARCHAR(64)    NOT NULL,
    model        VARCHAR(32)    NOT NULL,
    vram_total   INTEGER        NOT NULL DEFAULT 0,
    vram_used    INTEGER        NOT NULL DEFAULT 0,
    utilization  INTEGER        NOT NULL DEFAULT 0,
    temperature  INTEGER        NOT NULL DEFAULT 0,
    status       VARCHAR(16)    NOT NULL DEFAULT 'IDLE',
    current_task VARCHAR(200),
    pod_name     VARCHAR(64),
    cost_per_hour NUMERIC(10,2) NOT NULL DEFAULT 0,
    region       VARCHAR(64)    NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_t4_gpu_node_code UNIQUE (code),
    CONSTRAINT uk_ai_t4_gpu_node_name UNIQUE (name),
    CONSTRAINT chk_ai_t4_gpu_node_status CHECK (status IN ('IDLE','BUSY','OFFLINE','RESERVED'))
);

COMMENT ON TABLE  ai_t4_gpu_node IS 'T4 算力管理·GPU 节点表（/ai/compute 节点监控）';
COMMENT ON COLUMN ai_t4_gpu_node.code IS '业务码（gpu-*，与 name 同值，前端主键锚）';
COMMENT ON COLUMN ai_t4_gpu_node.model IS 'GPU 型号：A100/H100/V100/T4（mock 规格；不加 chk 留新型号扩展）';
COMMENT ON COLUMN ai_t4_gpu_node.vram_total IS '显存总量（GB）';
COMMENT ON COLUMN ai_t4_gpu_node.vram_used IS '显存已用（GB）';
COMMENT ON COLUMN ai_t4_gpu_node.utilization IS '算力利用率（%）';
COMMENT ON COLUMN ai_t4_gpu_node.temperature IS '温度（℃）';
COMMENT ON COLUMN ai_t4_gpu_node.status IS '状态：IDLE/BUSY/OFFLINE/RESERVED';
COMMENT ON COLUMN ai_t4_gpu_node.current_task IS '当前任务描述（IDLE/OFFLINE 可空）';
COMMENT ON COLUMN ai_t4_gpu_node.pod_name IS 'K8s Pod 名（可空）';
COMMENT ON COLUMN ai_t4_gpu_node.cost_per_hour IS '每小时成本（元）';
CREATE INDEX IF NOT EXISTS idx_ai_t4_gpu_node_status ON ai_t4_gpu_node (status);

CREATE TABLE IF NOT EXISTS ai_t4_gpu_quota (
    quota_id       BIGSERIAL      PRIMARY KEY,
    code           VARCHAR(40)    NOT NULL,
    department     VARCHAR(64)    NOT NULL,
    project        VARCHAR(120)   NOT NULL,
    gpu_hours      INTEGER        NOT NULL DEFAULT 0,
    gpu_hours_used INTEGER        NOT NULL DEFAULT 0,
    budget         NUMERIC(12,2)  NOT NULL DEFAULT 0,
    spent          NUMERIC(12,2)  NOT NULL DEFAULT 0,
    period         VARCHAR(16)    NOT NULL,
    status         VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_t4_gpu_quota_code UNIQUE (code),
    CONSTRAINT uk_ai_t4_gpu_quota_dpp UNIQUE (department, project, period),
    CONSTRAINT chk_ai_t4_gpu_quota_status CHECK (status IN ('ACTIVE','EXCEEDED','EXPIRED'))
);

COMMENT ON TABLE  ai_t4_gpu_quota IS 'T4 算力管理·部门配额表（/ai/compute 配额 Tab）';
COMMENT ON COLUMN ai_t4_gpu_quota.code IS '业务码（quota-*，前端主键锚）';
COMMENT ON COLUMN ai_t4_gpu_quota.gpu_hours IS '配额 GPU 时长（GPU·h）';
COMMENT ON COLUMN ai_t4_gpu_quota.gpu_hours_used IS '已用 GPU 时长（GPU·h）';
COMMENT ON COLUMN ai_t4_gpu_quota.budget IS '周期预算（元）';
COMMENT ON COLUMN ai_t4_gpu_quota.spent IS '已花费（元）';
COMMENT ON COLUMN ai_t4_gpu_quota.period IS '统计周期（如 2026-08 / 2026-Q3）';
COMMENT ON COLUMN ai_t4_gpu_quota.status IS '状态：ACTIVE/EXCEEDED/EXPIRED';
CREATE INDEX IF NOT EXISTS idx_ai_t4_gpu_quota_status ON ai_t4_gpu_quota (status);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_ai_t4_gpu_node_updated_at') THEN
        CREATE TRIGGER trg_ai_t4_gpu_node_updated_at BEFORE UPDATE ON ai_t4_gpu_node
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_ai_t4_gpu_quota_updated_at') THEN
        CREATE TRIGGER trg_ai_t4_gpu_quota_updated_at BEFORE UPDATE ON ai_t4_gpu_quota
            FOR EACH ROW EXECUTE FUNCTION ai_set_updated_at();
    END IF;
END $$;

-- ---------- 种子：12 GPU 节点（逐字 mock；ON CONFLICT 幂等；code=name 天然稳定锚） ----------
INSERT INTO ai_t4_gpu_node (code, name, model, vram_total, vram_used, utilization, temperature,
                            status, current_task, pod_name, cost_per_hour, region)
VALUES
  ('gpu-a100-01', 'gpu-a100-01', 'A100', 80, 68, 87, 72, 'BUSY', '客户流失预测 v3.2 重训练', 'train-mdl-001', 28, '上海-可用区A'),
  ('gpu-a100-02', 'gpu-a100-02', 'A100', 80, 74, 92, 78, 'BUSY', '皮肤影像分类 v1.6 训练', 'train-cv-014', 28, '上海-可用区A'),
  ('gpu-a100-03', 'gpu-a100-03', 'A100', 80, 0, 0, 38, 'IDLE', NULL, NULL, 28, '上海-可用区B'),
  ('gpu-h100-01', 'gpu-h100-01', 'H100', 80, 76, 95, 83, 'BUSY', '营销文案 LLM 微调', 'train-llm-002', 58, '北京-可用区A'),
  ('gpu-h100-02', 'gpu-h100-02', 'H100', 80, 12, 8, 42, 'RESERVED', '预留：推荐模型 20:00 训练', NULL, 58, '北京-可用区A'),
  ('gpu-v100-01', 'gpu-v100-01', 'V100', 32, 28, 76, 68, 'BUSY', '门店销量预测定时任务', 'pred-sales-007', 16, '上海-可用区A'),
  ('gpu-v100-02', 'gpu-v100-02', 'V100', 32, 0, 0, 35, 'IDLE', NULL, NULL, 16, '上海-可用区B'),
  ('gpu-t4-01', 'gpu-t4-01', 'T4', 16, 11, 54, 62, 'BUSY', '客服意图在线推理', 'infer-nlp-031', 8, '广州-可用区A'),
  ('gpu-t4-02', 'gpu-t4-02', 'T4', 16, 9, 48, 58, 'BUSY', '流失预测在线推理', 'infer-churn-022', 8, '广州-可用区A'),
  ('gpu-t4-03', 'gpu-t4-03', 'T4', 16, 0, 0, 28, 'OFFLINE', NULL, NULL, 8, '广州-可用区B'),
  ('gpu-t4-04', 'gpu-t4-04', 'T4', 16, 0, 0, 34, 'IDLE', NULL, NULL, 8, '广州-可用区B'),
  ('gpu-a100-04', 'gpu-a100-04', 'A100', 80, 62, 81, 71, 'BUSY', '推荐模型 DeepFM 训练', 'train-rec-009', 28, '北京-可用区B')
ON CONFLICT (code) DO NOTHING;

-- ---------- 种子：6 部门配额（逐字 mock；ON CONFLICT 幂等） ----------
INSERT INTO ai_t4_gpu_quota (code, department, project, gpu_hours, gpu_hours_used,
                             budget, spent, period, status)
VALUES
  ('quota-churn', '数据智能部', '客户流失预测', 500, 342, 14000, 9576, '2026-08', 'ACTIVE'),
  ('quota-sales', '数据智能部', '销量预测', 200, 186, 3200, 2976, '2026-08', 'ACTIVE'),
  ('quota-derm', '皮肤科', '皮肤影像分类', 300, 312, 8400, 8736, '2026-08', 'EXCEEDED'),
  ('quota-llm', '营销中心', '文案生成 LLM', 800, 425, 46400, 24650, '2026-Q3', 'ACTIVE'),
  ('quota-rec', '营销中心', '推荐模型训练', 400, 158, 11200, 4424, '2026-08', 'ACTIVE'),
  ('quota-cs', '客户体验部', '智能客服意图', 120, 120, 1920, 1920, '2026-07', 'EXPIRED')
ON CONFLICT (code) DO NOTHING;
