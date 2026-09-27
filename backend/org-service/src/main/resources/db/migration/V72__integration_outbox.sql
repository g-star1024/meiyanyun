-- ================================================================
-- V72 · T3 数据中台 出站消息表（T3-B2 · DESIGN-T3 §三 · 单向镜像红线）
-- 属主 org-service（V71 之后顺号）；铁律0：仅新增 integration_outbox；既有表零改动
-- uk(connector_id, txn_no) 幂等锚：同步重放不重复落库不重复外呼；retry 复用原 txn_no
-- outbox_no = OB-yyyyMMdd-seq（库内 maxSeqOfDay 照 B95 D7 / ContractRepository 先例）
-- ================================================================

CREATE TABLE IF NOT EXISTS integration_outbox (
    id            BIGSERIAL    PRIMARY KEY,
    outbox_no     VARCHAR(40)  NOT NULL,
    connector_id  BIGINT       NOT NULL REFERENCES integration_connector(id),
    biz_type      VARCHAR(16)  NOT NULL,
    txn_no        VARCHAR(64)  NOT NULL,
    amount        NUMERIC(14,2),
    local_sent    BOOLEAN      NOT NULL DEFAULT true,
    remote_ack    BOOLEAN      NOT NULL DEFAULT false,
    reconciled    BOOLEAN      NOT NULL DEFAULT false,
    status        VARCHAR(8)   NOT NULL DEFAULT 'PENDING',
    error_msg     VARCHAR(256),
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reconciled_at TIMESTAMPTZ,
    CONSTRAINT uq_integration_outbox_no     UNIQUE (outbox_no),
    CONSTRAINT uq_integration_outbox_txn    UNIQUE (connector_id, txn_no),
    CONSTRAINT chk_integration_outbox_biz   CHECK (biz_type IN ('ORDER_PAY','REFUND','INVOICE','VOUCHER','CONTACT','AD_CLICK')),
    CONSTRAINT chk_integration_outbox_status CHECK (status IN ('PENDING','ACK','FAILED','MATCHED','LONG','SHORT'))
);

COMMENT ON TABLE  integration_outbox              IS 'T3 数据中台 出站消息（单向镜像：只从本地业务表向外推送，绝不反向写资金池）';
COMMENT ON COLUMN integration_outbox.outbox_no    IS '出站消息号 OB-yyyyMMdd-seq（库内 maxSeqOfDay 生成）';
COMMENT ON COLUMN integration_outbox.connector_id IS '连接器 id（FK→integration_connector）';
COMMENT ON COLUMN integration_outbox.biz_type     IS 'ORDER_PAY=已支付单 / REFUND=退款 / INVOICE=发票 / VOUCHER=凭证 / CONTACT=客户 / AD_CLICK=广告点击（本批仅 ORDER_PAY）';
COMMENT ON COLUMN integration_outbox.txn_no       IS '业务单号（txn 域 order_no；幂等锚半边）';
COMMENT ON COLUMN integration_outbox.amount       IS '金额（元；txn 侧分换算 movePointLeft(2)）';
COMMENT ON COLUMN integration_outbox.local_sent   IS '本地已发送标志（Outbox 语义：消息已发出）';
COMMENT ON COLUMN integration_outbox.remote_ack   IS '三方已确认（真实外呼 2xx 置 true；对账 matched=remote_ack 口径）';
COMMENT ON COLUMN integration_outbox.reconciled   IS '已对账（T+1 对账批次置 true）';
COMMENT ON COLUMN integration_outbox.status       IS 'PENDING=待外呼 / ACK=三方已确认 / FAILED=外呼失败可重发 / MATCHED=对账一致 / LONG/SHORT=长短款（无三方账单源当前不产生，留 §7）';
COMMENT ON COLUMN integration_outbox.error_msg    IS '外呼失败如实文案';
COMMENT ON COLUMN integration_outbox.occurred_at  IS '业务发生时刻（txn 侧支付时间）';
COMMENT ON COLUMN integration_outbox.reconciled_at IS '对账时刻';

CREATE INDEX IF NOT EXISTS idx_integration_outbox_status_reconciled ON integration_outbox (status, reconciled);
CREATE INDEX IF NOT EXISTS idx_integration_outbox_occurred          ON integration_outbox (occurred_at DESC);
