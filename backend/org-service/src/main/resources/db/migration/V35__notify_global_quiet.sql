-- V35__notify_global_quiet.sql
-- 美研云门店中台 - 域⑧平台基建：全局免打扰时段纳入外部依赖配置窗口（P5-B60 卡4 / L39 收口）
-- 版本号：全库共享 flyway_schema_history 全局递增，V34（external_integration 建表）之后首个空号 V35。
-- 属主：org-service。
-- 内容：①value_kind 放开 QUIET_WINDOW（PG 不支持改 CHECK，DROP+ADD 重建，含原三值）；
--       ②播种固定目录第 8 行 NOTIFY_GLOBAL_QUIET，窗口存 config_json，enabled 即免打扰开关。
-- 幂等：ALTER 在全新库也成立（V34 必先执行）；INSERT ... ON CONFLICT DO NOTHING。
-- 全新库/正式库/种子库同脚本执行。

ALTER TABLE external_integration
    DROP CONSTRAINT IF EXISTS ck_external_integration_kind;
ALTER TABLE external_integration
    ADD CONSTRAINT ck_external_integration_kind
        CHECK (value_kind IN ('URL','SECRET','SWITCH','QUIET_WINDOW'));

-- 固定目录第 8 行（与 IntegrationCatalog 枚举同码同名；管理端可改窗口/开关，禁止删除）
INSERT INTO external_integration
    (integration_code, category, integration_name, value_kind, enabled, bool_value, config_json, remark)
VALUES
    ('NOTIFY_GLOBAL_QUIET', 'NOTIFY_GATEWAY', '全局免打扰时段', 'QUIET_WINDOW', FALSE, NULL,
     '{"start":"22:00","end":"08:00"}',
     '未启用：非紧急通知的短信/企微/邮件按各渠道配置实时发送，不限时段；启用后时段内非 URGENT 通知延后发送，站内信与紧急通知不受影响')
ON CONFLICT (integration_code) DO NOTHING;
