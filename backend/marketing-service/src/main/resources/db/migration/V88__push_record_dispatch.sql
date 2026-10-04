-- 棒⑧卡2：push_record 外发腿四列（客户侧营销推送外发适配器）。
-- status 历史行保持 NULL = 外发腿上线前纯落库记录（不追标 SENT，诚实口径）；
-- 新行由 PushDispatchService 写 SENT/FAILED/DEAD/SKIPPED。
-- 全库 flyway_schema_history 共享，customer 已占 V85-V87，本迁移自 V88 起。
ALTER TABLE push_record ADD COLUMN IF NOT EXISTS status VARCHAR(16);
ALTER TABLE push_record ADD COLUMN IF NOT EXISTS channel_msg_id VARCHAR(64);
ALTER TABLE push_record ADD COLUMN IF NOT EXISTS error VARCHAR(512);
ALTER TABLE push_record ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ;
