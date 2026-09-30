-- V83__audit_chain_exemption.sql
-- 美研云门店中台 - audit-service：审计哈希链断链豁免登记表（棒③卡1）
-- 版本号说明：全库共享 flyway_schema_history 全局递增版本号，实测全库最大 V82，本迁移取 V83。
-- 创建时间: 2026-09-30
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   04-backlog L43：prod 审计链巡检恒报 5 处历史断链（id 258/302/380/520/650，
--   缺失 id 区段 174-176/248-257/301/379/381/519/522-524/647-656 导致的结构性断链，
--   parent_exists=0，append-only 不可 UPDATE 回改），verifyChain brokenAtId 恒报 258，
--   巡检告警失去信噪比。用户 2026-09-30 拍板方向①：建豁免登记机制——
--   已知历史断链经登记豁免后与「新增断链」分离呈现，verifyChain 的 ok 只由新增断链决定。
--
-- 设计：
--   · audit_log 本体一行不动（append-only 不可篡改原则不破）；
--   · 本表自身同为 append-only：只 INSERT，应用层不提供任何 UPDATE/DELETE；
--   · audit_log_id 唯一索引即幂等键：同一断链节点重复登记返回已存在记录，不产生副作用；
--   · prev_hash/cur_hash 为登记时刻断链节点快照（audit_log 不可变，快照恒等于现值）；
--   · 不加外键：全新库 Flyway 先于 JPA 执行，此刻 audit_log 尚未由 ddl-auto 建出，
--     FK 会在全新库初始化时失败，故仅以应用层 findById 校验节点存在性。
--
-- 幂等与时序：
--   CREATE TABLE IF NOT EXISTS + CREATE UNIQUE INDEX IF NOT EXISTS——
--   全新库与现网库均可重入；随后 Hibernate ddl-auto=update 见表已存在仅校验列，不重建。

CREATE TABLE IF NOT EXISTS audit_chain_exemption (
    id            BIGSERIAL PRIMARY KEY,
    audit_log_id  BIGINT       NOT NULL,
    prev_hash     VARCHAR(64)  NOT NULL,
    cur_hash      VARCHAR(64)  NOT NULL,
    reason        TEXT         NOT NULL,
    registered_by VARCHAR(32)  NOT NULL,
    registered_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_audit_chain_exemption_log
    ON audit_chain_exemption (audit_log_id);

COMMENT ON TABLE audit_chain_exemption IS '审计哈希链断链豁免登记（append-only）：已知历史断链登记后与新增断链分离呈现，audit_log 本体不动';
