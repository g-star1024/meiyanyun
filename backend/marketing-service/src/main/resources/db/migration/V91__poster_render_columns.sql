-- V91__poster_render_columns.sql
-- 美研云门店中台 - 棒⑧卡4：poster_record 海报渲染产物三列（渲染上传链路落值）。
-- 版本号：全库共享 flyway_schema_history 全局递增，V90（org 对象存储目录）之后首个空号 V91。
-- 属主：marketing-service。
-- 内容：poster_record 新增渲染产物三列——render_object_key varchar(200)（定位符
--   bucket/objectKey，本地兜底时 bucket 为固定本地桶名）、render_uploaded_at timestamptz
--   （最近上传时间）、render_size bigint（字节数）。三列全可空：NULL=尚未上传渲染产物，
--   历史行诚实口径不追标。
-- 幂等：三列独立 ALTER ... ADD COLUMN IF NOT EXISTS；全新库/正式库/种子库同脚本执行。

ALTER TABLE poster_record ADD COLUMN IF NOT EXISTS render_object_key varchar(200);
ALTER TABLE poster_record ADD COLUMN IF NOT EXISTS render_uploaded_at timestamptz;
ALTER TABLE poster_record ADD COLUMN IF NOT EXISTS render_size bigint;
