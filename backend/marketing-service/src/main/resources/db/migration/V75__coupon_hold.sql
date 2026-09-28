-- V75__coupon_hold.sql
-- 美研云门店中台 - 域⑤营销域：C 端会员持券表 coupon_hold 纳入 Flyway 版本链（C-B5 批次）
-- 版本号说明：全库共享 flyway_schema_history 全局递增版本号，V58~V74 由其他服务持有
--   （V74 = c-service c_member_auth），本迁移接续取 V75，归 marketing-service 持有。
-- 创建时间: 2026-09-29
-- 数据库: PostgreSQL 15+
--
-- 纳管表（1 张）：
--   1) coupon_hold  C 端会员持券（小程序自助领取落账；与 B 端发放共用 coupon_template.issued_qty
--      库存口径，防超发由 CouponService 实例 synchronized 锁串行化，本表只记归属与状态）
--
-- 与历史迁移的关系：
--   全新表，无历史持有方。CREATE TABLE IF NOT EXISTS：
--     · 现网库：表不存在则建出；若已被 JPA ddl-auto 抢先建出则整句跳过，零数据风险；
--     · 全新库：一次到位；随后 Hibernate ddl-auto=update 对结构一致的表 no-op。
--   注意：仅 CREATE TABLE IF NOT EXISTS，不对存量表做 ALTER。
--
-- 约束/索引口径：唯一约束与 CHECK 内联在建表语句中（仅对全新库生效）；索引一律
--   CREATE INDEX IF NOT EXISTS，现网库缺索引也会幂等补齐。

-- ============================================================
-- 1. coupon_hold C 端会员持券
-- ============================================================
CREATE TABLE IF NOT EXISTS coupon_hold (
    id           BIGSERIAL     PRIMARY KEY,
    coupon_id    VARCHAR(24)   NOT NULL,
    customer_id  VARCHAR(64)   NOT NULL,
    idem_key     VARCHAR(128)  NOT NULL,
    status       VARCHAR(8)    NOT NULL DEFAULT 'HELD',
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_coupon_hold_idem UNIQUE (idem_key),
    CONSTRAINT ck_coupon_hold_status CHECK
        (status IN ('HELD','USED','EXPIRED','REVOKED'))
);

CREATE INDEX IF NOT EXISTS idx_coupon_hold_customer ON coupon_hold (customer_id, status);
CREATE INDEX IF NOT EXISTS idx_coupon_hold_coupon ON coupon_hold (coupon_id);
