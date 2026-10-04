-- V89__esign_provider.sql
-- 美研云门店中台 - 棒⑧卡3：电子签接入位配置目录（厂商无关适配层消费）。
-- 版本号：全库共享 flyway_schema_history 全局递增，V88（marketing 推送外发列）之后首个空号 V89。
-- 属主：org-service。
-- 内容：播种固定目录第 15-16 行——ESIGN_DIRECT（SWITCH，config_json 承载厂商参数模板；
--   enabled 且 boolValue=true 时 txn 合同走电子签签署门禁：发起签署→客户签署回调→方可生效）
--   + ESIGN_SECRET（SECRET，厂商接入密钥密文，发送调用 Bearer 与回调验签 HMAC 共用）。
-- 幂等：INSERT ... ON CONFLICT DO NOTHING；不 ALTER 任何存量表（value_kind CHECK 已含
--   SWITCH/SECRET，category 无约束可立新类 ESIGN）。全新库/正式库/种子库同脚本执行。

INSERT INTO external_integration
    (integration_code, category, integration_name, value_kind, enabled, bool_value, config_json, remark)
VALUES
    ('ESIGN_DIRECT', 'ESIGN', '电子签直连厂商 API（接入位）', 'SWITCH', FALSE, FALSE,
     '{"provider":"","endpoint":"","appId":"","callbackPath":"/api/txn/esign/callback"}',
     '未启用：合同无签署门禁，草稿可直接生效（对现存流程零影响）；启用后 txn 走电子签流程（发起签署→客户签署回调→方可生效），须配齐扩展参数（provider/endpoint/appId）+ ESIGN_SECRET 密钥，任一缺失发起签署按 SKIPPED 诚实降级不发送、不静默回落线下'),
    ('ESIGN_SECRET', 'ESIGN', '电子签厂商密钥（接入调用与回调验签共用）', 'SECRET', FALSE, NULL, NULL,
     '未配置：电子签开关启用时发起签署按 SKIPPED 不发送、回调验签 fail-closed 拒绝（503）；密钥 AES-GCM 密文存储，仅内部快照下发')
ON CONFLICT (integration_code) DO NOTHING;
