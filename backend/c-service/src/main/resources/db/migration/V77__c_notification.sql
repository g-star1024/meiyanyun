-- ================================================================
-- V77 · C 端会员消息通知表（C 端移动端批 C-B6 · DESIGN-C端移动端专项设计-2026-09-28 §四端点 #9）
-- 属主 c-service（V76 之后顺号，全库共享 flyway_schema_history 全局版本链）；铁律0：仅新增 c_notification；既有表零改动
-- 通知页数据源：B 端 notification 表 recipient 系 staff 维度（C 端会员无源）→ c-service 自有表本地读写，免 internal 跨服务
-- customer_id 逻辑引用 customer.customer_id（业务编号 VARCHAR(16)），零物理 FK 照 V63-V66/V74 先例
-- idx(customer_id, is_read) 支撑通知列表行级隔离 + 未读角标聚合；markAllRead 写路径 UPDATE is_read 随拍定
-- ================================================================

CREATE TABLE IF NOT EXISTS c_notification (
    id          BIGSERIAL   PRIMARY KEY,
    customer_id VARCHAR(16) NOT NULL,
    type        VARCHAR(16) NOT NULL,
    title       VARCHAR(128) NOT NULL,
    body        VARCHAR(512) NOT NULL DEFAULT '',
    link        VARCHAR(256),
    is_read     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_c_notification_type CHECK (type IN ('appt','promo','system'))
);

CREATE INDEX IF NOT EXISTS idx_c_notification_customer_read ON c_notification (customer_id, is_read);

COMMENT ON TABLE  c_notification             IS 'C 端会员消息通知（通知页列表/未读角标/全部已读写路径；c-service 自有表免 internal）';
COMMENT ON COLUMN c_notification.customer_id IS '逻辑引用 B 端 customer.customer_id（业务编号 VARCHAR(16)，零物理 FK）；行级隔离锚';
COMMENT ON COLUMN c_notification.type        IS 'appt=预约 / promo=营销 / system=系统（照前端 Notif 接口三值）';
COMMENT ON COLUMN c_notification.title       IS '通知标题';
COMMENT ON COLUMN c_notification.body        IS '通知正文';
COMMENT ON COLUMN c_notification.link        IS '点击跳转路由（可空；照前端 Notif.to）';
COMMENT ON COLUMN c_notification.is_read     IS '已读标记（open 单条已读 + markAllRead 批量已读）';
COMMENT ON COLUMN c_notification.created_at  IS '投递时刻（列表按此倒序）';
COMMENT ON COLUMN c_notification.updated_at  IS '最近更新时刻';
