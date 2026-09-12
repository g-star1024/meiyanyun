-- V14__marketing_content_push_baseline.sql
-- 美研云门店中台 - 域⑤营销域：海报/短视频/直播/触达 5 表纳入 Flyway（B41 批次三/共三批，收尾）
-- 版本号说明：全库共享 flyway_schema_history 全局递增版本号，V12（券赠金 8 表）、
--   V13（渠道/素材/违禁词 3 表）之后取 V14。本批落定后 marketing-service 全部 18 张域表
--   （含 V11 的 marketing_cfg/auto_grant_state）纳管完毕，JPA ddl-auto=update 退居净兜底。
-- 创建时间: 2026-09-12
-- 数据库: PostgreSQL 15+
--
-- 纳管表（5 张，结构以现网 pg_dump --schema-only 物理结构为准）：
--   1) poster_template  海报模板；
--   2) poster_record    海报分发/获客业绩记录；
--   3) short_video      短视频投放数据；
--   4) live_session     直播场次数据（start_time 为不带时区时间，对齐现网物理结构）；
--   5) push_record      触达记录（周频限 3 条审计依据；customer_id 外键挂 customer 域，
--      compose 已固化 marketing 依赖 customer healthy 后启动，全新库外键时序安全）。
--
-- 幂等与时序（与 V11/V12/V13 同一范式）：
--   CREATE TABLE IF NOT EXISTS——现网库五表均已由 JPA 建出，整句跳过；全新库由本迁移先于
--   Hibernate 建出全列目标态，update no-op。存量表不做 ALTER（push_record.push_type 扩宽与
--   dedup_key 补列历史上由 PushSchemaInitializer 自愈，本文件目标态已直接是 varchar(16)+dedup_key）。

-- ============================================================
-- 1. poster_template 海报模板
-- ============================================================
CREATE TABLE IF NOT EXISTS poster_template (
    template_id      VARCHAR(24)   PRIMARY KEY,
    template_name    VARCHAR(64)   NOT NULL,
    style            VARCHAR(12)   NOT NULL,
    accent           VARCHAR(8)    NOT NULL,
    default_title    VARCHAR(64)   NOT NULL,
    default_subtitle VARCHAR(128)  NOT NULL,
    status           VARCHAR(8)    NOT NULL,
    uses             INTEGER       NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL
);

-- ============================================================
-- 2. poster_record 海报分发/获客业绩
-- ============================================================
CREATE TABLE IF NOT EXISTS poster_record (
    poster_id       VARCHAR(24)   PRIMARY KEY,
    template_id     VARCHAR(24)   NOT NULL,
    template_name   VARCHAR(64)   NOT NULL,
    title           VARCHAR(64)   NOT NULL,
    subtitle        VARCHAR(128),
    style           VARCHAR(12)   NOT NULL,
    accent          VARCHAR(8)    NOT NULL,
    project         VARCHAR(64)   NOT NULL,
    referrer_name   VARCHAR(32),
    commission_rate INTEGER       NOT NULL,
    scan            INTEGER       NOT NULL,
    visit           INTEGER       NOT NULL,
    share           INTEGER       NOT NULL,
    lead            INTEGER       NOT NULL,
    deal            INTEGER       NOT NULL,
    deal_amount     BIGINT        NOT NULL,
    status          VARCHAR(10)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL
);

-- ============================================================
-- 3. short_video 短视频投放数据
-- ============================================================
CREATE TABLE IF NOT EXISTS short_video (
    video_id      VARCHAR(24)   PRIMARY KEY,
    title         VARCHAR(64)   NOT NULL,
    platform      VARCHAR(16)   NOT NULL,
    tags          VARCHAR(256)  NOT NULL,
    plays         INTEGER       NOT NULL,
    likes         INTEGER       NOT NULL,
    deal_count    INTEGER       NOT NULL,
    deal_amount   BIGINT        NOT NULL,
    published_at  DATE          NOT NULL
);

-- ============================================================
-- 4. live_session 直播场次（start_time 不带时区，对齐现网）
-- ============================================================
CREATE TABLE IF NOT EXISTS live_session (
    session_id          VARCHAR(24)   PRIMARY KEY,
    title               VARCHAR(64)   NOT NULL,
    platform            VARCHAR(16)   NOT NULL,
    host                VARCHAR(32),
    intro               VARCHAR(500),
    status              VARCHAR(12)   NOT NULL,
    start_time          TIMESTAMP     NOT NULL,
    viewers             INTEGER       NOT NULL,
    link_clicks         INTEGER       NOT NULL,
    deal_count          INTEGER       NOT NULL,
    deal_amount         BIGINT        NOT NULL,
    mounted_coupon_ids  VARCHAR(512)  NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL
);

-- ============================================================
-- 5. push_record 触达记录（外键挂 customer 域；COMMENT 幂等回放）
-- ============================================================
CREATE TABLE IF NOT EXISTS push_record (
    push_id      BIGSERIAL     PRIMARY KEY,
    customer_id  VARCHAR(16)   NOT NULL,
    push_type    VARCHAR(16)   NOT NULL,
    content      VARCHAR(256)  NOT NULL,
    dedup_key    VARCHAR(32),
    sent_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT push_record_customer_id_fkey
        FOREIGN KEY (customer_id) REFERENCES customer (customer_id)
);
COMMENT ON TABLE push_record IS '触达记录：每周每客户 ≤3 条（weekly_push_limit），发送前过违禁词校验';
