-- V12__marketing_coupon_grant_baseline.sql
-- 美研云门店中台 - 域⑤营销域：券与赠金条线 8 表纳入 Flyway 版本链（B41 批次一/共三批）
-- 版本号说明：全库共享 flyway_schema_history 全局递增版本号，V11 由本服务持有
--   （marketing_cfg + auto_grant_state 首批纳管），本迁移接续取 V12；V13/V14 同批随后，
--   三批合计 16 张，marketing-service 全部域表纳管完毕（marketing_cfg/auto_grant_state 已在 V11）。
-- 创建时间: 2026-09-12
-- 数据库: PostgreSQL 15+
--
-- 纳管表（8 张，结构以现网 pg_dump --schema-only 物理结构为准）：
--   1) coupon_template       券模板（V3 曾由 customer-service DROP+重建，本迁移改回本服务持有基线）
--   2) coupon_grant          发券批次（同上，V3 历史持有）
--   3) campaign              营销活动（同上，V3 历史持有）
--   4) coupon_writeoff_record 券核销流水（B34 风控名单/B40 存量 SST 处置均在此表）
--   5) coupon_writeoff_chain  核销漏斗看板（正常/异常/待处理三段计数）
--   6) customer_grant        客户赠金账户（B35 收银台抵扣/B37 消费满赠/B39 退款回加）
--   7) grant_rule            赠金规则（满赠/活动/新客礼）
--   另：grant_deduction（赠金抵扣流水，含 B39 origin_biz_ref 新列）随券赠金域一并收录。
--
-- 与历史 V3 的关系：
--   V3（customer-service 类路径）对 coupon_template/coupon_grant/campaign 做 DROP TABLE IF EXISTS
--   + CREATE，仅在全新库、customer-service 先于 marketing-service 启动时执行（compose 已固化该
--   健康依赖顺序）；现网库三表早已存在，V3 历史版本只在 flyway_schema_history 留痕、不再重放。
--   本迁移全部 CREATE TABLE IF NOT EXISTS：
--     · 现网库：表均已由 JPA/V3 建出，整句跳过，零数据风险；
--     · 全新库：V3 先建 3 表（本句跳过），本迁移补齐另 5 表并以本文件定义全部目标态；
--       随后 Hibernate ddl-auto=update 对结构一致的表 no-op。
--   注意：仅 CREATE TABLE IF NOT EXISTS，不对存量表做 ALTER；存量库若缺列仍由 Hibernate update
--   补齐（与 V9/V11 同一范式）。B39 grant_deduction.origin_biz_ref 现网已由 update 补出，
--   本文件收录后全新库一次到位。
--
-- 约束/索引口径：唯一约束与 CHECK 内联在建表语句中（仅对全新库生效）；索引一律
--   CREATE INDEX IF NOT EXISTS，现网库缺索引也会幂等补齐（V3 已建的同名索引自动跳过）。

-- ============================================================
-- 1. coupon_template 券模板（金额券 AMOUNT / 折扣券 RATE / 套餐券 PACKAGE）
-- ============================================================
CREATE TABLE IF NOT EXISTS coupon_template (
    coupon_id         VARCHAR(24)    PRIMARY KEY,
    coupon_name       VARCHAR(64)    NOT NULL,
    coupon_type       VARCHAR(8)     NOT NULL,
    face_value        BIGINT         NOT NULL,
    threshold         BIGINT         NOT NULL DEFAULT 0,
    total_qty         INTEGER        NOT NULL,
    issued_qty        INTEGER        NOT NULL DEFAULT 0,
    used_qty          INTEGER        NOT NULL DEFAULT 0,
    status            VARCHAR(8)     NOT NULL DEFAULT 'DRAFT',
    grant_scope       VARCHAR(10)    NOT NULL DEFAULT 'ALL',
    grant_scope_name  VARCHAR(64),
    package_items     VARCHAR(2000),
    campaign_id       VARCHAR(24),
    coupon_code       VARCHAR(32),
    valid_start       DATE,
    valid_end         DATE,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_coupon_template_type CHECK
        (coupon_type IN ('AMOUNT','RATE','PACKAGE')),
    CONSTRAINT ck_coupon_template_status CHECK
        (status IN ('DRAFT','ACTIVE','DISABLED')),
    CONSTRAINT ck_coupon_template_scope CHECK
        (grant_scope IN ('ALL','NEW','SEGMENT','DESIGNATED'))
);

-- ============================================================
-- 2. coupon_grant 发券批次
-- ============================================================
CREATE TABLE IF NOT EXISTS coupon_grant (
    grant_id     VARCHAR(24)  PRIMARY KEY,
    coupon_id    VARCHAR(24)  NOT NULL,
    coupon_name  VARCHAR(64)  NOT NULL,
    grant_scope  VARCHAR(10)  NOT NULL,
    target_name  VARCHAR(64)  NOT NULL,
    grant_count  INTEGER      NOT NULL,
    status       VARCHAR(8)   NOT NULL DEFAULT 'GRANTED',
    granted_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    operator     VARCHAR(32)  NOT NULL,
    CONSTRAINT ck_coupon_grant_scope CHECK
        (grant_scope IN ('ALL','NEW','SEGMENT','DESIGNATED')),
    CONSTRAINT ck_coupon_grant_status CHECK
        (status IN ('GRANTED','FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_coupon_grant_coupon ON coupon_grant (coupon_id);

-- ============================================================
-- 3. campaign 营销活动（满减/折扣/券包/赠品/新客/VIP 日）
-- ============================================================
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id    VARCHAR(24)  PRIMARY KEY,
    campaign_name  VARCHAR(64)  NOT NULL,
    campaign_type  VARCHAR(16)  NOT NULL,
    status         VARCHAR(10)  NOT NULL DEFAULT 'DRAFT',
    channels       VARCHAR(500),
    start_date     DATE,
    end_date       DATE,
    budget         BIGINT       NOT NULL DEFAULT 0,
    spent          BIGINT       NOT NULL DEFAULT 0,
    target_amount  BIGINT       NOT NULL DEFAULT 0,
    actual_amount  BIGINT       NOT NULL DEFAULT 0,
    new_customers  INTEGER      NOT NULL DEFAULT 0,
    store_scope    VARCHAR(64),
    owner          VARCHAR(32),
    remark         VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_campaign_type CHECK
        (campaign_type IN ('FULL_REDUCE','DISCOUNT','COUPON_PACK','GIFT','NEWBIE','VIP_DAY')),
    CONSTRAINT ck_campaign_status CHECK
        (status IN ('DRAFT','SCHEDULED','RUNNING','ENDED','CANCELLED'))
);
CREATE INDEX IF NOT EXISTS idx_campaign_status ON campaign (status);

-- ============================================================
-- 4. coupon_writeoff_record 券核销流水（风控凭证，禁止物理删除）
-- ============================================================
CREATE TABLE IF NOT EXISTS coupon_writeoff_record (
    writeoff_id       VARCHAR(24)  PRIMARY KEY,
    channel           VARCHAR(16)  NOT NULL,
    coupon_code       VARCHAR(32)  NOT NULL,
    coupon_id         VARCHAR(24),
    coupon_name       VARCHAR(64)  NOT NULL,
    customer_name     VARCHAR(32)  NOT NULL,
    customer_phone    VARCHAR(20)  NOT NULL,
    discount_fen      BIGINT       NOT NULL,
    operator          VARCHAR(32)  NOT NULL,
    order_amount_fen  BIGINT       NOT NULL,
    reason            VARCHAR(200),
    status            VARCHAR(10)  NOT NULL,
    store_code        VARCHAR(16)  NOT NULL,
    store_name        VARCHAR(64)  NOT NULL,
    verified_at       TIMESTAMPTZ  NOT NULL
);

-- ============================================================
-- 5. coupon_writeoff_chain 核销漏斗看板（segment 沿用现网中文枚举）
-- ============================================================
CREATE TABLE IF NOT EXISTS coupon_writeoff_chain (
    chain_id  VARCHAR(24)  PRIMARY KEY,
    segment   VARCHAR(8)   NOT NULL,
    cnt       INTEGER      NOT NULL,
    period    VARCHAR(8)   NOT NULL DEFAULT '本月',
    CONSTRAINT coupon_writeoff_chain_segment_check CHECK
        (segment IN ('正常核销','异常核销','待处理'))
);

-- ============================================================
-- 6. customer_grant 客户赠金账户（幂等键 uk_cg_idem）
-- ============================================================
CREATE TABLE IF NOT EXISTS customer_grant (
    id              BIGSERIAL    PRIMARY KEY,
    customer_id     VARCHAR(64)  NOT NULL,
    amount_fen      BIGINT       NOT NULL,
    balance_fen     BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    expire_at       TIMESTAMPTZ  NOT NULL,
    rule_id         VARCHAR(24),
    source_biz_ref  VARCHAR(128),
    idem_key        VARCHAR(128) NOT NULL,
    store_code      VARCHAR(16),
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_cg_idem UNIQUE (idem_key)
);

-- ============================================================
-- 7. grant_rule 赠金规则（applicable_stores 空串=全部门店）
-- ============================================================
CREATE TABLE IF NOT EXISTS grant_rule (
    rule_id             VARCHAR(24)  PRIMARY KEY,
    name                VARCHAR(64)  NOT NULL,
    grant_type          VARCHAR(24)  NOT NULL,
    threshold_fen       BIGINT,
    grant_amount_fen    BIGINT       NOT NULL,
    expire_months       INTEGER      NOT NULL,
    applicable_stores   TEXT,
    status              VARCHAR(16)  NOT NULL,
    priority            INTEGER      NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL
);

-- ============================================================
-- 8. grant_deduction 赠金抵扣流水（B39 起含 origin_biz_ref 退款锚点列）
-- ============================================================
CREATE TABLE IF NOT EXISTS grant_deduction (
    id                BIGSERIAL    PRIMARY KEY,
    grant_id          BIGINT       NOT NULL,
    customer_id       VARCHAR(64)  NOT NULL,
    amount_fen        BIGINT       NOT NULL,
    balance_after_fen BIGINT       NOT NULL,
    change_type       VARCHAR(16)  NOT NULL,
    biz_ref           VARCHAR(64)  NOT NULL,
    origin_biz_ref    VARCHAR(64),
    store_code        VARCHAR(16),
    operator          VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_gd_ref_grant UNIQUE (biz_ref, grant_id)
);
