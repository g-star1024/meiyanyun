-- V93__kafka_direct.sql
-- 美研云门店中台 - 棒⑧卡5：Kafka 事件发布接入位配置目录（marketing 领域事件外发链路消费）。
-- 版本号：全库共享 flyway_schema_history 全局递增，V90（org 对象存储目录）/V91（marketing
--   poster_record 渲染三列）/V92（org V36 rename 避让）之后首个空号 V93。
-- 属主：org-service。
-- 内容：播种固定目录第 19-20 行——KAFKA_DIRECT（SWITCH，config_json 承载 Kafka 参数模板
--   bootstrapServers/clientIdPrefix；enabled 且 boolValue=true 且 meiyun.event-publisher=mq 时
--   marketing 领域事件经 KafkaDomainEventPublisher 真实外发，未启用即使 mq 模式也如实降级
--   Logging 口径，对现存流程零影响）
--   + KAFKA_SECRET（SECRET，SASL 凭据密文 AES-GCM，仅内部快照下发；PLAINTEXT 内网直连可留空）。
-- 幂等：INSERT ... ON CONFLICT DO NOTHING；不 ALTER 任何存量表（value_kind CHECK 已含
--   SWITCH/SECRET，category 无约束可立新类 KAFKA）。全新库/正式库/种子库同脚本执行。
-- 边界：本卡＝Kafka 事件发布装配位＋CDC/Kafka 数据源连通探测真实化；Debezium/Canal CDC 引擎
--   与真实 Kafka broker 基建仍属数据中台二期（DESIGN-T3 §7 口径）。V87（data_source 表）已
--   applied 双库，validate-on-migrate 下不改其头注（避免 checksum mismatch），「归 v2」边界
--   说明改落本文件头注与代码注释（棒⑧卡5 定案）。

INSERT INTO external_integration
    (integration_code, category, integration_name, value_kind, enabled, bool_value, config_json, remark)
VALUES
    ('KAFKA_DIRECT', 'KAFKA', 'Kafka 事件发布直连（接入位）', 'SWITCH', FALSE, FALSE,
     '{"bootstrapServers":"meiyun-kafka:9092","clientIdPrefix":"meiyun-marketing"}',
     '未启用：领域事件走 Logging 兜底（event-publisher=log 默认，零影响）；启用且 mq 模式经 KafkaDomainEventPublisher 真实外发，须配 bootstrapServers/clientIdPrefix，SASL 另配 KAFKA_SECRET；参数缺失按 SKIPPED 诚实降级、不伪造已发送'),
    ('KAFKA_SECRET', 'KAFKA', 'Kafka 密钥（SASL）', 'SECRET', FALSE, NULL, NULL,
     '未配置：PLAINTEXT 内网直连可留空；SASL 集群启用时密钥缺失按 SKIPPED 诚实降级如实落日志；密钥 AES-GCM 密文存储，仅内部快照下发')
ON CONFLICT (integration_code) DO NOTHING;
