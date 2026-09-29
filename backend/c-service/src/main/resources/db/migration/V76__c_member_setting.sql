-- ================================================================
-- V76 · C 端会员消息偏好设置表（C 端移动端批 C-B6 · DESIGN-C端移动端专项设计-2026-09-28 §四端点 #9）
-- 属主 c-service（V75 之后顺号，全库共享 flyway_schema_history 全局版本链）；铁律0：仅新增 c_member_setting；既有表零改动
-- 设置页三开关（订单/预约/营销通知）数据源：B 端 notify_preference 系 staff 维度表，C 端会员无源 → c-service 自有表本地读写，免 internal 跨服务
-- customer_id 逻辑引用 customer.customer_id（业务编号 VARCHAR(16)），零物理 FK 照 V63-V66/V74 先例
-- uk(customer_id) 幂等锚：一个会员档案一行偏好；GET 缺行回落默认（order/appt=true, promo=false）照前端写死初值
-- ================================================================

CREATE TABLE IF NOT EXISTS c_member_setting (
    id                 BIGSERIAL   PRIMARY KEY,
    customer_id        VARCHAR(16) NOT NULL,
    notify_order       BOOLEAN     NOT NULL DEFAULT TRUE,
    notify_appointment BOOLEAN     NOT NULL DEFAULT TRUE,
    notify_promo       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_c_member_setting_customer UNIQUE (customer_id)
);

COMMENT ON TABLE  c_member_setting                    IS 'C 端会员消息偏好设置（设置页三开关持久化；c-service 自有表本地读写免 internal）';
COMMENT ON COLUMN c_member_setting.customer_id        IS '逻辑引用 B 端 customer.customer_id（业务编号 VARCHAR(16)，零物理 FK）；一会员一行';
COMMENT ON COLUMN c_member_setting.notify_order       IS '订单通知开关（默认开，照前端写死初值）';
COMMENT ON COLUMN c_member_setting.notify_appointment IS '预约通知开关（默认开，照前端写死初值）';
COMMENT ON COLUMN c_member_setting.notify_promo       IS '营销通知开关（默认关，照前端写死初值）';
COMMENT ON COLUMN c_member_setting.created_at         IS '建档时刻';
COMMENT ON COLUMN c_member_setting.updated_at         IS '最近更新时刻';
