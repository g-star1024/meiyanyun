-- V36__notify_direct_channels.sql
-- 美研云门店中台 - 域⑧平台基建：员工侧三通道直连官方 API 配置目录（棒⑧卡1）
-- 版本号：全库共享 flyway_schema_history 全局递增，V35（全局免打扰）之后首个空号 V36。
-- 属主：org-service。
-- 内容：播种固定目录第 9-14 行——短信/邮件/企微三通道各两行：
--   NOTIFY_*_DIRECT（SWITCH，config_json 承载厂商参数模板；enabled 且 boolValue=true 时
--   消费侧直连官方 API 优先于 webhook 中继）+ NOTIFY_*_SECRET（SECRET，直连密钥密文）。
-- 幂等：INSERT ... ON CONFLICT DO NOTHING；不 ALTER 任何存量表（value_kind CHECK 已含
--   SWITCH/SECRET，V35 放宽后无需重建约束）。全新库/正式库/种子库同脚本执行。

INSERT INTO external_integration
    (integration_code, category, integration_name, value_kind, enabled, bool_value, config_json, remark)
VALUES
    ('NOTIFY_SMS_DIRECT', 'NOTIFY_GATEWAY', '短信直连官方 API（阿里云）', 'SWITCH', FALSE, FALSE,
     '{"provider":"aliyun","endpoint":"dysmsapi.aliyuncs.com","accessKeyId":"","signName":"","templateCode":""}',
     '未启用：短信走 NOTIFY_SMS_GATEWAY webhook 中继，未配中继则 SKIPPED 仅站内信送达；启用后直连阿里云短信，须配齐扩展参数（accessKeyId/签名/模板码）+ NOTIFY_SMS_SECRET 密钥 + 员工已登记手机号，任一缺失按 SKIPPED 诚实降级不发送、不静默回落中继'),
    ('NOTIFY_SMS_SECRET', 'NOTIFY_GATEWAY', '短信直连密钥（阿里云 AccessKeySecret）', 'SECRET', FALSE, NULL, NULL,
     '未配置：短信直连开关启用时因缺密钥按 SKIPPED 不发送；密钥 AES-GCM 密文存储，仅内部快照下发'),
    ('NOTIFY_EMAIL_DIRECT', 'NOTIFY_GATEWAY', '邮件直连 SMTP 服务', 'SWITCH', FALSE, FALSE,
     '{"host":"","port":465,"username":"","from":"","ssl":true}',
     '未启用：邮件走 NOTIFY_EMAIL_GATEWAY webhook 中继，未配中继则 SKIPPED；启用后直连 SMTP 发信，须配齐扩展参数（host/账号/发件人）+ NOTIFY_EMAIL_SECRET 授权码 + 员工已登记邮箱，任一缺失按 SKIPPED 诚实降级不发送'),
    ('NOTIFY_EMAIL_SECRET', 'NOTIFY_GATEWAY', '邮件直连密钥（SMTP 授权码）', 'SECRET', FALSE, NULL, NULL,
     '未配置：邮件直连开关启用时因缺授权码按 SKIPPED 不发送；密钥 AES-GCM 密文存储，仅内部快照下发'),
    ('NOTIFY_WECHAT_DIRECT', 'NOTIFY_GATEWAY', '企业微信直连官方 API（应用消息）', 'SWITCH', FALSE, FALSE,
     '{"corpId":"","agentId":0}',
     '未启用：企微走 NOTIFY_WECHAT_GATEWAY webhook 中继，未配中继则 SKIPPED；启用后直连企微应用消息，须配齐扩展参数（corpId/agentId）+ NOTIFY_WECHAT_SECRET 应用 secret + 员工已登记企微账号，任一缺失按 SKIPPED 诚实降级不发送'),
    ('NOTIFY_WECHAT_SECRET', 'NOTIFY_GATEWAY', '企微直连密钥（应用 secret）', 'SECRET', FALSE, NULL, NULL,
     '未配置：企微直连开关启用时因缺应用 secret 按 SKIPPED 不发送；密钥 AES-GCM 密文存储，仅内部快照下发')
ON CONFLICT (integration_code) DO NOTHING;
