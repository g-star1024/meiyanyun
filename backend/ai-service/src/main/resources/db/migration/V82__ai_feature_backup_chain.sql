-- V82 ai_feature_binding 备用模型链（棒①卡3 多供应商 failover）
-- 版本链登记：本库 Flyway 版本区间 V26-V81 已占用·本迁移取 V82·后续新迁移请从 V83 起并同步更新本注释。
-- 职责：功能绑定增备用模型链列·invoke 主模型调用失败（5xx/超时/拒绝）时按链逐个重试·每段结果落 ai_invoke_log 沉淀 failover 轨迹。
ALTER TABLE ai_feature_binding ADD COLUMN IF NOT EXISTS backup_model_ids VARCHAR(255);
COMMENT ON COLUMN ai_feature_binding.backup_model_ids IS '备用模型链（逗号分隔 model_id·按序切换·不含主模型）：主模型调用失败时按链逐个重试·每段成功/失败均落 ai_invoke_log 记实际 provider/model';
