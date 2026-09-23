-- ============================================================
-- V49 营销配置补列：marketing_cfg.care_templates 关怀模板 JSONB（P5-B90）
-- 背景：V48 施工时实体 MarketingCfg.careTemplates 已加但迁移漏登该列，
--      Hibernate schema-validation 启动失败；V48 已应用不可改，版本号全局递增取 V49 补登。
-- 播种：CareTemplateDataInitializer @Order(16) 启动期播种 5 条默认模板（幂等：非空跳过）。
-- ============================================================

ALTER TABLE marketing_cfg ADD COLUMN IF NOT EXISTS care_templates JSONB;

COMMENT ON COLUMN marketing_cfg.care_templates IS '关怀模板 JSONB 数组 [{id,name,channel,content}]（P5-B90，/m3-care 模板面板，CareTemplateDataInitializer @Order(16) 播种 5 条对齐前端 mock）';
