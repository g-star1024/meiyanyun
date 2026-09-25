-- ============================================================
-- V52 触点事件快照表（P5-B98 / DESIGN-T2 §3-D4）
-- 单表：touch_event（触点快照，仅存不算——归因算法 B69 定案延后）
-- 说明：flyway_schema_history 全库共享，版本号全局递增（全局最大 V51 后取 V52）。
-- 幂等可重入：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS。
-- 不建物理外键：customer_id / ref_id 均为逻辑引用（见列 COMMENT）。
-- 采集幂等：(client_token, touch_type) 部分唯一索引（照 V46 uk_landing_page_client_token 先例），
--   重放/并发撞唯一约束 → 捕 DataIntegrityViolationException 返 dedup=true，不重复计数。
-- ddl-auto=validate 硬约束（B48 卡3 收口）：本 DDL 与 TouchEvent 实体映射逐列对齐。
-- ============================================================

CREATE TABLE IF NOT EXISTS touch_event (
    id           BIGSERIAL    PRIMARY KEY,
    customer_id  VARCHAR(16),
    channel      VARCHAR(32)  NOT NULL,
    touch_type   VARCHAR(16)  NOT NULL,
    ref_type     VARCHAR(32),
    ref_id       VARCHAR(64),
    client_token VARCHAR(64),
    payload      JSONB,
    at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_touch_event_type CHECK (touch_type IN ('LANDING_VISIT', 'LANDING_LEAD', 'POSTER_SCAN', 'PUSH_SEND', 'RETURNBACK'))
);

COMMENT ON TABLE  touch_event IS '触点事件快照（P5-B98 / DESIGN-T2 §3-D4）：五类 LANDING_VISIT/LANDING_LEAD/POSTER_SCAN/PUSH_SEND/RETURNBACK，仅存不算（归因算法 B69 定案延后）；T2-01 采集监控直读本表';
COMMENT ON COLUMN touch_event.customer_id IS '客户号（逻辑引用，可空）：匿名访问/留资未匹配为 NULL；PUSH_SEND/RETURNBACK 旁路按既有记录带入';
COMMENT ON COLUMN touch_event.channel IS '触点渠道：LANDING 落地页 / SMS|WECOM|WECHAT_MP 推送渠道 / DOUYIN|RED|MEITUAN 广告渠道 / POSTER 海报（§7 待 C 端）';
COMMENT ON COLUMN touch_event.touch_type IS '触点类型五值：LANDING_VISIT 落地页访问 / LANDING_LEAD 落地页留资 / POSTER_SCAN 海报扫码 / PUSH_SEND 推送发送 / RETURNBACK 渠道回传';
COMMENT ON COLUMN touch_event.ref_type IS '来源单据类型：LANDING_PAGE / PUSH_RECORD / CHANNEL_RETURNBACK';
COMMENT ON COLUMN touch_event.ref_id IS '来源单据号（逻辑引用）：ref_type 对应表主键（landing_page.page_id / push_record.push_id / channel_returnback.id）';
COMMENT ON COLUMN touch_event.client_token IS '采集幂等令牌：落地页访客端生成，(client_token,touch_type) 重放不重复计数（idemKey 范式，D8）';
COMMENT ON COLUMN touch_event.payload IS '触点原始快照 JSON：访问 UA/Referer、留资表单字段、推送渠道、回传事件等';
COMMENT ON COLUMN touch_event.at IS '触点发生时刻（落库时刻，TIMESTAMPTZ）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_touch_event_client_token_type
    ON touch_event (client_token, touch_type) WHERE client_token IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_touch_event_type     ON touch_event (touch_type);
CREATE INDEX IF NOT EXISTS idx_touch_event_customer ON touch_event (customer_id);
