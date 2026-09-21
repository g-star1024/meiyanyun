-- V41__staff_role_org_scope.sql
-- 美研云门店中台 - 组织树二批兼岗模型：staff_role 主键扩三列（staff_id, role_code, org_code）（P5-B87 卡1 / D1 方案B）
-- 版本号：全库共享 flyway_schema_history 全局递增，V40（customer_merge，customer-service 属主）之后首个空号 V41。
-- 属主：org-service。
-- 内容：①staff_role ADD COLUMN org_code varchar(16) NOT NULL DEFAULT ''（''=全局兼岗，通吃所有大区/门店）；
--       ②主键 (staff_id, role_code) 扩为 (staff_id, role_code, org_code)——同角色可按范围授予多行
--         （如华东 R-HD 一行＋华北 R-HB 一行），DROP 旧 pkey 后 ADD 三列新 pkey。
-- 既有数据：种子库现 25 行 20 人兼岗 org_code 由 DEFAULT 自动落 ''，行为与现状完全一致，零迁移成本。
-- 幂等与时序：
--   ALTER TABLE IF EXISTS / ADD COLUMN IF NOT EXISTS / DROP CONSTRAINT IF EXISTS——
--     · 现网/种子库：表已存在，三段 ALTER 精准落列与主键；
--     · 全新库：Flyway 先于 JPA 执行，staff_role 尚未建（IF EXISTS 全跳过），
--       随后 Hibernate ddl-auto=update 按实体（@Id 三字段）直接建出目标结构。
--   重入安全：PG 重建主键仍命名 staff_role_pkey，再次执行 DROP+ADD 结果不变。
-- 全新库/正式库/种子库同脚本执行。

ALTER TABLE IF EXISTS staff_role
    ADD COLUMN IF NOT EXISTS org_code varchar(16) NOT NULL DEFAULT '';

ALTER TABLE IF EXISTS staff_role
    DROP CONSTRAINT IF EXISTS staff_role_pkey;

ALTER TABLE IF EXISTS staff_role
    ADD PRIMARY KEY (staff_id, role_code, org_code);
