-- ================================================================
-- V74 · C 端会员账号绑定表（C 端移动端批 C-B1 · DESIGN-C端移动端专项设计-2026-09-28 §三）
-- 属主 c-service（V73 之后顺号，全库共享 flyway_schema_history 全局版本链）；铁律0：仅新增 c_member_auth；既有表零改动
-- 「独立会员体系」唯一新表：微信 openid ↔ B 端 customer 档案的绑定层；六域业务数据全复用 B 端既有表
-- customer_id 逻辑引用 customer.customer_id（业务编号 VARCHAR(16)），零物理 FK 照 V63-V66 先例；
--   DESIGN-C §三原稿 BIGINT 系按通用主键假设落笔，施工实证 customer 表主键为 VARCHAR(16) 业务编号，按铁律0 修正
-- uk(openid) 幂等锚：同一微信账号全库唯一绑定；dev-login 占位 openid=dev_<phone> 不复用真实 openid 段
-- ================================================================

CREATE TABLE IF NOT EXISTS c_member_auth (
    id            BIGSERIAL     PRIMARY KEY,
    openid        VARCHAR(64)   NOT NULL,
    unionid       VARCHAR(64),
    customer_id   VARCHAR(16),
    phone         VARCHAR(20),
    nickname      VARCHAR(64),
    avatar        VARCHAR(256),
    status        VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_c_member_auth_openid UNIQUE (openid),
    CONSTRAINT chk_c_member_auth_status CHECK (status IN ('ACTIVE','DISABLED'))
);

COMMENT ON TABLE  c_member_auth              IS 'C 端会员账号绑定（微信 openid↔customer 档案；「独立会员体系」唯一新表）';
COMMENT ON COLUMN c_member_auth.openid       IS '微信 code2session 返回 openid（dev-login 占位=dev_<phone>）；全库唯一';
COMMENT ON COLUMN c_member_auth.unionid      IS '微信 unionid（多应用打通用；未配置开放平台为空）';
COMMENT ON COLUMN c_member_auth.customer_id  IS '逻辑引用 B 端 customer.customer_id（业务编号 VARCHAR(16)，零物理 FK）；首约/首单时补绑';
COMMENT ON COLUMN c_member_auth.phone        IS '手机号（dev-login 建档写入；微信手机号授权留 §7）';
COMMENT ON COLUMN c_member_auth.nickname     IS '微信昵称（端侧授权上送；可空）';
COMMENT ON COLUMN c_member_auth.avatar       IS '微信头像 URL（端侧授权上送；可空）';
COMMENT ON COLUMN c_member_auth.status       IS 'ACTIVE=正常 / DISABLED=停用（停用后 auth/me 403，登录拒绝签发）';
COMMENT ON COLUMN c_member_auth.last_login_at IS '最近登录时刻（每次成功登录刷新）';
COMMENT ON COLUMN c_member_auth.created_at   IS '建档时刻';
COMMENT ON COLUMN c_member_auth.updated_at   IS '最近更新时刻';
