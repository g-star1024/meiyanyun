-- V1__init.sql
-- 美研云门店中台 - 系统域表初始化（Flyway 精管）
-- 创建时间: 2026-08-31
-- 数据库: PostgreSQL 15+
--
-- 分层约定：
--   * Flyway 只精管「系统域」表（sys_*）：BIGINT 主键、自洽、跨服务共享。
--   * 「业务域」表（customer / appointment / order_info / contract / emr /
--     inventory_* / payment / refund 等）由各微服务 JPA 实体驱动，
--     Hibernate `ddl-auto: update` 在 Flyway 迁移后自动创建/补齐，
--     主键多为业务编码（varchar），不在此手写 DDL，以免与实体定义漂移。

-- ============================================================
-- 系统域：部门、用户、角色、权限、字典、日志
-- ============================================================

-- 部门表（连锁门店组织架构）
CREATE TABLE IF NOT EXISTS sys_dept (
    id BIGSERIAL PRIMARY KEY,
    dept_code VARCHAR(50) NOT NULL UNIQUE,
    dept_name VARCHAR(100) NOT NULL,
    parent_id BIGINT REFERENCES sys_dept(id),
    dept_type VARCHAR(20) NOT NULL, -- GROUP/REGION/STORE
    store_code VARCHAR(50), -- 门店编码（仅 STORE 类型）
    address VARCHAR(255),
    contact_phone VARCHAR(20),
    manager_id BIGINT, -- 部门负责人
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/INACTIVE
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT
);

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    real_name VARCHAR(50) NOT NULL,
    phone VARCHAR(20) UNIQUE,
    email VARCHAR(100),
    dept_id BIGINT REFERENCES sys_dept(id),
    position VARCHAR(50), -- 职位
    employee_no VARCHAR(50), -- 工号
    avatar_url VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/INACTIVE/LOCKED
    last_login_at TIMESTAMP,
    last_login_ip VARCHAR(50),
    pwd_updated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT
);

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGSERIAL PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL UNIQUE,
    role_name VARCHAR(100) NOT NULL,
    role_type VARCHAR(20) NOT NULL, -- SYSTEM/CUSTOM
    description VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT
);

-- 权限表
CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGSERIAL PRIMARY KEY,
    perm_code VARCHAR(100) NOT NULL UNIQUE,
    perm_name VARCHAR(100) NOT NULL,
    perm_type VARCHAR(20) NOT NULL, -- MENU/BUTTON/API
    parent_id BIGINT REFERENCES sys_permission(id),
    resource_url VARCHAR(255), -- 前端路由或后端接口
    icon VARCHAR(50),
    sort_order INT DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id)
);

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id BIGINT NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES sys_permission(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, permission_id)
);

-- 数据字典表（支持动态增删；种子见 V2__seed_dictionaries.sql）
CREATE TABLE IF NOT EXISTS sys_dictionary (
    id BIGSERIAL PRIMARY KEY,
    category VARCHAR(50) NOT NULL, -- 字典分类：扁平枚举名 CUSTOMER_SOURCE / ORDER_STATUS ...
    dict_code VARCHAR(50) NOT NULL, -- 字典编码
    dict_value VARCHAR(50) NOT NULL, -- 字典值
    dict_label VARCHAR(100) NOT NULL, -- 显示标签
    dict_color VARCHAR(20), -- 颜色标识：success/warning/danger/info/default/primary
    dict_icon VARCHAR(50), -- 图标
    sort_order INT DEFAULT 0,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT,
    UNIQUE(category, dict_code, dict_value)
);

-- 操作日志表（审计 append-only）
CREATE TABLE IF NOT EXISTS sys_operation_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES sys_user(id),
    username VARCHAR(50),
    operation VARCHAR(100) NOT NULL, -- 操作描述
    method VARCHAR(255), -- 请求方法
    params TEXT, -- 请求参数
    ip VARCHAR(50),
    duration INT, -- 执行时长（毫秒）
    status VARCHAR(20), -- SUCCESS/FAIL
    error_msg TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 登录日志表
CREATE TABLE IF NOT EXISTS sys_login_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES sys_user(id),
    username VARCHAR(50),
    login_type VARCHAR(20), -- LOGIN/LOGOUT
    ip VARCHAR(50),
    user_agent VARCHAR(255),
    status VARCHAR(20), -- SUCCESS/FAIL
    message VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 系统域索引
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_dict_category ON sys_dictionary(category);
CREATE INDEX IF NOT EXISTS idx_dict_enabled ON sys_dictionary(is_enabled);
CREATE INDEX IF NOT EXISTS idx_op_log_user ON sys_operation_log(user_id);
CREATE INDEX IF NOT EXISTS idx_op_log_created ON sys_operation_log(created_at);
CREATE INDEX IF NOT EXISTS idx_login_log_user ON sys_login_log(user_id);
CREATE INDEX IF NOT EXISTS idx_login_log_created ON sys_login_log(created_at);
CREATE INDEX IF NOT EXISTS idx_user_dept ON sys_user(dept_id);
CREATE INDEX IF NOT EXISTS idx_user_role_user ON sys_user_role(user_id);
CREATE INDEX IF NOT EXISTS idx_role_perm_role ON sys_role_permission(role_id);

-- ============================================================
-- 系统域初始数据
-- ============================================================

-- 初始化超级管理员（密码: admin123，BCrypt 占位，生产须重置）
INSERT INTO sys_user (username, password_hash, real_name, phone, status, created_by)
VALUES ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EO', '系统管理员', '13800000000', 'ACTIVE', 0)
ON CONFLICT (username) DO NOTHING;

-- 初始化角色
INSERT INTO sys_role (role_code, role_name, role_type, description, created_by) VALUES
('SUPER_ADMIN', '超级管理员', 'SYSTEM', '拥有所有权限', 0),
('STORE_MANAGER', '店长', 'CUSTOM', '门店管理权限', 0),
('CONSULTANT', '咨询师', 'CUSTOM', '客户咨询与预约', 0),
('DOCTOR', '医生', 'CUSTOM', '病历与治疗', 0),
('CASHIER', '收银员', 'CUSTOM', '订单与支付', 0)
ON CONFLICT (role_code) DO NOTHING;

-- 初始化部门（示例）
INSERT INTO sys_dept (dept_code, dept_name, dept_type, store_code, created_by) VALUES
('HQ', '总部', 'GROUP', NULL, 0),
('STORE001', '旗舰店', 'STORE', 'S001', 0),
('STORE002', '分店一', 'STORE', 'S002', 0)
ON CONFLICT (dept_code) DO NOTHING;

-- ============================================================
-- 版本记录（业务表由 Hibernate ddl-auto=update 自动维护）
-- ============================================================
CREATE TABLE IF NOT EXISTS schema_version (
    version VARCHAR(20) PRIMARY KEY,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    description VARCHAR(255)
);

INSERT INTO schema_version (version, description) VALUES
('V1', '系统域表结构：部门/用户/角色/权限/字典/日志 + 初始管理员与角色')
ON CONFLICT (version) DO NOTHING;
