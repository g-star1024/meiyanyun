-- V13__marketing_channel_asset_baseline.sql
-- 美研云门店中台 - 域⑤营销域：渠道回传/素材库/违禁词 3 表纳入 Flyway（B41 批次二/共三批）
-- 版本号说明：全库共享 flyway_schema_history 全局递增版本号，V12（券与赠金条线 8 表）之后取 V13。
-- 创建时间: 2026-09-12
-- 数据库: PostgreSQL 15+
--
-- 纳管表（3 张，结构以现网 pg_dump --schema-only 物理结构为准）：
--   1) channel_returnback 外部广告渠道回传原始记录（抖音/小红书/美团，CLICK/LEAD/CONVERSION）；
--      (channel_code, biz_ref) 幂等唯一 + channel/status 两条查询索引；
--   2) marketing_asset    营销素材库元数据（IMAGE/VIDEO/COPY/LOGO，scope=ALL/SPECIFIED，
--      tags/store_codes 均为 JSON 文本，B40 已把 2 条 SST 定向素材改投 ALL）；
--   3) forbidden_word     违禁词库（category+word 唯一，Redis 缓存的持久源）。
--
-- 幂等与时序（与 V11/V12 同一范式）：
--   CREATE TABLE IF NOT EXISTS——现网库三表均已由 JPA ddl-auto=update 建出，整句跳过；
--   全新库由本迁移先于 Hibernate 按现网物理结构建出，随后 update no-op。
--   不对存量表做 ALTER；索引 CREATE INDEX IF NOT EXISTS 现网缺则幂等补齐。

-- ============================================================
-- 1. channel_returnback 外部渠道回传记录
-- ============================================================
CREATE TABLE IF NOT EXISTS channel_returnback (
    id                   BIGSERIAL    PRIMARY KEY,
    channel_code         VARCHAR(32)  NOT NULL,
    external_user_id     VARCHAR(64),
    event_type           VARCHAR(24)  NOT NULL,
    biz_ref              VARCHAR(64),
    sig_nonce            VARCHAR(64),
    payload              TEXT,
    status               VARCHAR(16)  NOT NULL,
    matched_customer_id  VARCHAR(64),
    received_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_crb_chan_bizref UNIQUE (channel_code, biz_ref)
);
CREATE INDEX IF NOT EXISTS idx_crb_channel ON channel_returnback (channel_code);
CREATE INDEX IF NOT EXISTS idx_crb_status ON channel_returnback (status);

-- ============================================================
-- 2. marketing_asset 营销素材库（tags/store_codes 为 JSON 文本数组）
-- ============================================================
CREATE TABLE IF NOT EXISTS marketing_asset (
    asset_id     VARCHAR(24)   PRIMARY KEY,
    asset_name   VARCHAR(64)   NOT NULL,
    type         VARCHAR(8)    NOT NULL,
    tags         VARCHAR(512)  NOT NULL,
    scope        VARCHAR(10)   NOT NULL,
    store_codes  VARCHAR(512)  NOT NULL,
    expire_at    DATE          NOT NULL,
    ref_count    INTEGER       NOT NULL,
    accent       VARCHAR(8)    NOT NULL,
    content      VARCHAR(1000),
    created_at   TIMESTAMPTZ   NOT NULL
);

-- ============================================================
-- 3. forbidden_word 违禁词库
-- ============================================================
CREATE TABLE IF NOT EXISTS forbidden_word (
    word_id     BIGSERIAL    PRIMARY KEY,
    category    VARCHAR(32)  NOT NULL,
    word        VARCHAR(64)  NOT NULL,
    enabled     BOOLEAN      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_forbidden_word_category_word UNIQUE (category, word)
);
