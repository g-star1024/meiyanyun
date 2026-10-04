-- V90__storage_provider.sql
-- 美研云门店中台 - 棒⑧卡4：对象存储接入位配置目录（marketing 海报渲染上传链路消费）。
-- 版本号：全库共享 flyway_schema_history 全局递增，V89（org 电子签目录）之后首个空号 V90。
-- 属主：org-service。
-- 内容：播种固定目录第 17-18 行——STORAGE_DIRECT（SWITCH，config_json 承载对象存储参数模板
--   provider/endpoint/bucket/region/accessKey；enabled 且 boolValue=true 时 marketing 海报渲染
--   上传走 S3/MinIO，未启用走本地磁盘兜底，对现存流程零影响）
--   + STORAGE_SECRET（SECRET，对象存储 secretKey 密文 AES-GCM，仅内部快照下发）。
-- 幂等：INSERT ... ON CONFLICT DO NOTHING；不 ALTER 任何存量表（value_kind CHECK 已含
--   SWITCH/SECRET，category 无约束可立新类 STORAGE）。全新库/正式库/种子库同脚本执行。

INSERT INTO external_integration
    (integration_code, category, integration_name, value_kind, enabled, bool_value, config_json, remark)
VALUES
    ('STORAGE_DIRECT', 'STORAGE', '对象存储直连（S3/MinIO 接入位）', 'SWITCH', FALSE, FALSE,
     '{"provider":"minio","endpoint":"http://meiyun-minio:9000","bucket":"","region":"us-east-1","accessKey":""}',
     '未启用：marketing 海报渲染上传走本地磁盘兜底（storage.local.root，对现存流程零影响）；启用后走 S3/MinIO，须配齐扩展参数（provider/endpoint/bucket/region/accessKey）+ STORAGE_SECRET 密钥，任一缺失渲染上传按 SKIPPED 诚实降级 503、不静默回落本地；bucket 按库隔离（正式库 meiyun-core / 种子库 meiyun-seed 各自配置）'),
    ('STORAGE_SECRET', 'STORAGE', '对象存储密钥（secretKey）', 'SECRET', FALSE, NULL, NULL,
     '未配置：对象存储开关启用时渲染上传按 SKIPPED 503 诚实降级；密钥 AES-GCM 密文存储，仅内部快照下发')
ON CONFLICT (integration_code) DO NOTHING;
