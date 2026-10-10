-- V95__storage_public_endpoint.sql
-- 美研云门店中台 - F3 卡2 ㉕：对象存储目录模板补 publicEndpoint 外轨字段（内外双轨修透）。
-- 版本号：全库共享 flyway_schema_history 全局递增，V94（finance outbox 哨兵）之后首个空号 V95。
-- 属主：org-service。
-- 背景：V90 播种模板仅单 endpoint（示例值 http://meiyun-minio:9000 为容器网络内轨地址），
--   预签名 URL 会以该 host 签发——浏览器/外部网络不可解析（验收问题 ㉕）。
-- 内容：STORAGE_DIRECT config_json 模板并入 "publicEndpoint":""（jsonb || 合并，重复执行同结果；
--   空串在解析侧按缺省=内轨 endpoint 兼容，棒⑧卡4 存量配置零迁移成本）；remark 同步登记
--   内外双轨与 tenant-ctx 链口径。存量已填真实值行仅追加键，不覆盖既有五字段。
-- tenant-ctx 链口径（登记）：租户/集团上下文不经存储链路传递——bucket 按库隔离
--   （meiyun-core/meiyun-seed）＋后端中介读写承担隔离边界；预签名 URL 仅外轨 host 重写不越界。

UPDATE external_integration
SET config_json = config_json || '{"publicEndpoint":""}'::jsonb,
    remark = '未启用：渲染上传走本地磁盘兜底；启用后走 S3/MinIO，须配 provider/endpoint/bucket/region/accessKey + STORAGE_SECRET，缺失则诚实降级 503 不回落本地；bucket 按库隔离；publicEndpoint 选填（㉕ 内外双轨）：endpoint=内轨承载 I/O，publicEndpoint=外轨仅预签名 URL host，缺省=内轨'
WHERE integration_code = 'STORAGE_DIRECT';
