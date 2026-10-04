-- ================================================================
-- V85 · T2 标签工厂计算结果集（棒⑥卡7 T2-03 真算落表）
-- 铁律0：仅新增 tag_factory_result；既有表零改动；零物理外键（照 V63-V84 先例）
-- 语义：发布（publish）时同事务整批重写该 factory 的成员集（先删后插），
--       仅存当前发布版本的成员；preview 试算不落表。
-- ================================================================

CREATE TABLE IF NOT EXISTS tag_factory_result (
    id          BIGSERIAL   PRIMARY KEY,
    factory_id  BIGINT      NOT NULL,
    customer_id VARCHAR(16) NOT NULL,
    tag_version VARCHAR(20) NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  tag_factory_result            IS 'T2 标签工厂计算结果集（发布版本成员名单）';
COMMENT ON COLUMN tag_factory_result.factory_id IS '标签工厂定义 ID（逻辑引用 tag_factory_def.id）';
COMMENT ON COLUMN tag_factory_result.customer_id IS '命中客户 ID（逻辑引用 customer.customer_id）';
COMMENT ON COLUMN tag_factory_result.tag_version IS '发布版本号（如 v1.0，对齐 tag_factory_def.versions）';
COMMENT ON COLUMN tag_factory_result.computed_at IS '计算（发布）时间';

CREATE INDEX IF NOT EXISTS idx_tag_factory_result_factory ON tag_factory_result (factory_id);
CREATE INDEX IF NOT EXISTS idx_tag_factory_result_customer ON tag_factory_result (customer_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tag_factory_result_member
    ON tag_factory_result (factory_id, customer_id, tag_version);
