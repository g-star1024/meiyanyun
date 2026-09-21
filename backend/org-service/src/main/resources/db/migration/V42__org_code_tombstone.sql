-- V42__org_code_tombstone.sql
-- 美研云门店中台 - 组织树二批兼岗模型：org_code_tombstone 组织编码回收站（P5-B87 卡3 / L38 物理删除 / D5）
-- 版本号：全库共享 flyway_schema_history 全局递增，V41（staff_role_org_scope，org-service 属主）之后首个空号 V42。
-- 属主：org-service。
-- 内容：节点物理删除后编码留痕禁复用——org_name/org_type 冗余便于列表检索，snapshot 存删除时整行 jsonb 快照供审计追溯；
--       新建节点撞 tombstone 编码 409「该编码已于 xx 删除回收，不可复用」（D5 本批禁复用，回收池管理留 Backlog）。
-- 幂等与时序：
--   CREATE TABLE IF NOT EXISTS——现网/种子库：表不存在则建出，已存在跳过；
--     · 全新库：Flyway 先于 JPA 执行，本表无对应实体（仅 JdbcTemplate 读写），唯一建表出处即本脚本。
--   重入安全：IF NOT EXISTS 重复执行结果不变；COMMENT 语句幂等覆盖。
-- 全新库/正式库/种子库同脚本执行。

CREATE TABLE IF NOT EXISTS org_code_tombstone (
    org_code    varchar(16) PRIMARY KEY,
    org_name    varchar(64) NOT NULL,
    org_type    varchar(8)  NOT NULL,
    deleted_by  varchar(32) NOT NULL,
    deleted_at  timestamptz NOT NULL DEFAULT now(),
    snapshot    jsonb       NOT NULL
);

COMMENT ON TABLE org_code_tombstone IS 'L38 组织编码回收站：节点物理删除后编码留痕禁复用（D5），snapshot 为删除时整行 jsonb 快照';
COMMENT ON COLUMN org_code_tombstone.deleted_by IS '删除操作人（DataScope.currentActor）';
