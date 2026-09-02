-- V3__marketing_tables.sql
-- 美研云门店中台 - M5 营销域表结构校正（Flyway 精管）
-- 创建时间: 2026-09-02
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   coupon_template / campaign / coupon_grant 三张营销表早先由 JPA ddl-auto=update
--   依据旧实体自动生成，落的是中文枚举 CHECK 约束（如 status IN ('进行中','已停用')、
--   campaign_type IN ('优惠券','拼团','直播')），且缺少 M5 写链路新增列
--   （coupon_type / grant_scope / spent / target_amount / actual_amount / new_customers）。
--   Hibernate update 只加列不改约束，NOT NULL 新列在存量行上又因空值失败，导致营销写链路 500。
--
-- 本迁移：DROP 旧错误表 → 按 M5 JPA 实体逐列重建（英文状态机，金额 bigint 存分）。
--   这三张表属「营销演示/运营数据」，不承载系统主数据，重建不影响 sys_* 与其它业务域；
--   fresh-DB 下 V1/V2 未建过这些表，DROP IF EXISTS 幂等安全。
--   重建后 marketing-service 的 ddl-auto=update 将全部 no-op（列已齐全）。

-- ============================================================
-- 优惠券模板（状态机：DRAFT 草稿 → ACTIVE 进行中 → DISABLED 已停用；EXPIRED 由有效期派生不落库）
-- ============================================================
DROP TABLE IF EXISTS coupon_grant CASCADE;
DROP TABLE IF EXISTS coupon_template CASCADE;
DROP TABLE IF EXISTS campaign CASCADE;

CREATE TABLE coupon_template (
    coupon_id        VARCHAR(24)   PRIMARY KEY,
    coupon_name      VARCHAR(64)   NOT NULL,
    coupon_type      VARCHAR(8)    NOT NULL,            -- AMOUNT 满减 / RATE 折扣 / PACKAGE 券包
    face_value       BIGINT        NOT NULL,            -- 面值（分）；RATE 存折扣×10（8.5 折=85）
    threshold        BIGINT        NOT NULL DEFAULT 0,  -- 使用门槛（分），0=无门槛
    total_qty        INTEGER       NOT NULL,            -- 总库存
    issued_qty       INTEGER       NOT NULL DEFAULT 0,  -- 已发放
    used_qty         INTEGER       NOT NULL DEFAULT 0,  -- 已核销
    status           VARCHAR(8)    NOT NULL DEFAULT 'DRAFT',  -- DRAFT/ACTIVE/DISABLED
    grant_scope      VARCHAR(10)   NOT NULL DEFAULT 'ALL',    -- ALL/NEW/SEGMENT/DESIGNATED
    grant_scope_name VARCHAR(64),
    package_items    VARCHAR(2000),                     -- 券包子项 JSON（仅 PACKAGE）
    campaign_id      VARCHAR(24),
    coupon_code      VARCHAR(32),
    valid_start      DATE,
    valid_end        DATE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_coupon_template_type   CHECK (coupon_type IN ('AMOUNT','RATE','PACKAGE')),
    CONSTRAINT ck_coupon_template_scope  CHECK (grant_scope IN ('ALL','NEW','SEGMENT','DESIGNATED')),
    CONSTRAINT ck_coupon_template_status CHECK (status IN ('DRAFT','ACTIVE','DISABLED'))
);

-- ============================================================
-- 优惠券发放台账（防超发；一次发放动作一条，库存为 0 时不产生记录由接口 409 拦截）
-- ============================================================
CREATE TABLE coupon_grant (
    grant_id    VARCHAR(24)   PRIMARY KEY,
    coupon_id   VARCHAR(24)   NOT NULL,
    coupon_name VARCHAR(64)   NOT NULL,
    grant_scope VARCHAR(10)   NOT NULL,              -- ALL/NEW/SEGMENT/DESIGNATED
    target_name VARCHAR(64)   NOT NULL,              -- 发放对象名称
    grant_count INTEGER       NOT NULL,              -- 本次实际发放张数
    status      VARCHAR(8)    NOT NULL DEFAULT 'GRANTED',  -- GRANTED/FAILED
    granted_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    operator    VARCHAR(32)   NOT NULL,
    CONSTRAINT ck_coupon_grant_scope  CHECK (grant_scope IN ('ALL','NEW','SEGMENT','DESIGNATED')),
    CONSTRAINT ck_coupon_grant_status CHECK (status IN ('GRANTED','FAILED'))
);
CREATE INDEX idx_coupon_grant_coupon ON coupon_grant(coupon_id);

-- ============================================================
-- 营销活动（状态机：DRAFT→SCHEDULED→RUNNING→ENDED；CANCELLED 旁路终态）
-- ============================================================
CREATE TABLE campaign (
    campaign_id    VARCHAR(24)   PRIMARY KEY,
    campaign_name  VARCHAR(64)   NOT NULL,
    campaign_type  VARCHAR(16)   NOT NULL,           -- FULL_REDUCE/DISCOUNT/COUPON_PACK/GIFT/NEWBIE/VIP_DAY
    status         VARCHAR(10)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT/SCHEDULED/RUNNING/ENDED/CANCELLED
    channels       VARCHAR(500),                     -- 渠道中文名 JSON 数组
    start_date     DATE,
    end_date       DATE,
    budget         BIGINT        NOT NULL DEFAULT 0, -- 预算（分）
    spent          BIGINT        NOT NULL DEFAULT 0, -- 已花费（分）
    target_amount  BIGINT        NOT NULL DEFAULT 0, -- 目标成交额（分）
    actual_amount  BIGINT        NOT NULL DEFAULT 0, -- 实际成交额（分）
    new_customers  INTEGER       NOT NULL DEFAULT 0, -- 引流新客数
    store_scope    VARCHAR(64),
    owner          VARCHAR(32),
    remark         VARCHAR(500),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_campaign_type   CHECK (campaign_type IN ('FULL_REDUCE','DISCOUNT','COUPON_PACK','GIFT','NEWBIE','VIP_DAY')),
    CONSTRAINT ck_campaign_status CHECK (status IN ('DRAFT','SCHEDULED','RUNNING','ENDED','CANCELLED'))
);
CREATE INDEX idx_campaign_status ON campaign(status);

-- ============================================================
-- 版本记录
-- ============================================================
INSERT INTO schema_version (version, description) VALUES
('V3', 'M5 营销域表结构校正：券模板/发券台账/活动重建为英文状态机 + 写链路新增列（金额 bigint 存分）')
ON CONFLICT (version) DO NOTHING;
