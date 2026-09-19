-- V39__fin_input_invoice.sql
-- 美研云门店中台 - B63 卡4 L86 进项税抵扣链路（进项发票登记簿＋增值税申报期登记簿）
-- 创建时间: 2026-09-19
-- 数据库: PostgreSQL 15+
--
-- 背景：
--   L86 前 fin_invoice / tax 仅覆盖销项视角：FinTaxView 进项抵扣恒 ¥0.00（前端
--   注释明写无数据源），FinInvoiceView 无进项 tab。本迁移落两张表支撑
--   「登记 → 用途确认 → 抵扣 → 进项转出 → 申报」全链：
--     fin_input_invoice 进项发票登记簿（五类扣税凭证，状态机驱动）
--     fin_tax_period    增值税申报期登记簿（按月/季，五金额申报快照，期末留抵）
--   法规口径（2026-01-01 施行的增值税法及实施条例 国令第826号）：
--     五类扣税凭证（实施条例 §11）：专票/海关缴款书/通行费电子普票/旅客运输/其他；
--     用途确认无 360 日期限硬卡（国税总局公告 2019 年第 45 号），系统只做票龄软提示。
--
-- 首启安全：
--   1. 本迁移纯 DDL 零 INSERT——两表基线为空，进项 tab/税务页读到空表，进项抵扣
--      诚实为 0，行为与上线前一致；
--   2. 申报期懒创建（POST /tax-periods/ensure 幂等 upsert），不随迁移播种期间行；
--   3. 重复票部分唯一索引：同销方税号＋发票号码仅允许一条「活跃票」，不抵扣终态票
--      （NON_DEDUCTIBLE）不参与约束，允许同票以不抵扣重复登记留痕。
--
-- 幂等：CREATE TABLE/INDEX IF NOT EXISTS，双库（meiyun_core / meiyun_seed）各自
--   Flyway 独立应用一次，可重入；迁移后 JPA ddl-auto=update 对本表 no-op。
--   金额三列（amount/net_amount/tax_amount/transfer_out_amount/申报五金额）均
--   BIGINT 存分，恒等式 amount = net_amount + tax_amount 落 CHECK。

CREATE TABLE IF NOT EXISTS fin_input_invoice (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    register_no         VARCHAR(24)   NOT NULL,             -- 内部登记号 PINV-yyyyMMdd-0001（独立序列）
    invoice_code        VARCHAR(32),                        -- 发票代码（专票有代码；电子/海关票据可空）
    invoice_no          VARCHAR(32)   NOT NULL,             -- 发票号码（外部税局号码，原样登记）
    invoice_kind        VARCHAR(16)   NOT NULL,             -- 扣税凭证五类（§11）：SPECIAL 专票 / CUSTOMS 海关缴款书 / TOLL 通行费电子普票 / PASSENGER 旅客运输 / OTHER 其他
    seller_name         VARCHAR(128)  NOT NULL,             -- 开票方名称（录入即活，冗余不跨服务关联）
    seller_tax_no       VARCHAR(32)   NOT NULL,             -- 开票方纳税人识别号（无税号场景以证件号占位）
    supplier_id         BIGINT,                             -- 可选软关联 store-service supplier.id（无物理外键，跨服务）
    amount              BIGINT        NOT NULL,             -- 价税合计（分）
    net_amount          BIGINT        NOT NULL,             -- 不含税净额（分，服务端价税分离 HALF_UP）
    tax_amount          BIGINT        NOT NULL,             -- 税额（分）
    tax_rate            DECIMAL(5,4)  NOT NULL,             -- 税率五档：0/0.01/0.03/0.06/0.13
    category            VARCHAR(16)   NOT NULL,             -- 采购用途沿用销项三档：SERVICE / PRODUCT / MEMBERSHIP
    purpose             VARCHAR(16)   NOT NULL DEFAULT 'PENDING', -- 用途确认：PENDING 待确认 / DEDUCT 抵扣 / NO_DEDUCT 不抵扣 / REFUND 退税（远期）
    status              VARCHAR(20)   NOT NULL DEFAULT 'UNCONFIRMED', -- UNCONFIRMED / CONFIRMED / DEDUCTED / TRANSFERRED_OUT / NON_DEDUCTIBLE
    nondeduct_reason    VARCHAR(16),                        -- 不抵扣/转出七码：WELFARE/LOSS_GOODS/LOSS_PRODUCT/LOSS_REAL_ESTATE/LOSS_CONSTRUCTION/LOAN_DAILY/OTHER
    transfer_out_amount BIGINT        NOT NULL DEFAULT 0,   -- 进项转出额（分）
    period_id           BIGINT,                             -- 抵扣归属申报期（软关联 fin_tax_period.id，无物理外键）
    invoice_date        DATE          NOT NULL,             -- 开票日期
    confirmed_at        TIMESTAMPTZ,                        -- 用途确认时间
    deducted_at         TIMESTAMPTZ,                        -- 抵扣时间
    transferred_at      TIMESTAMPTZ,                        -- 进项转出时间
    store_code          VARCHAR(16)   NOT NULL,             -- 数据域（DataScope.storeSpec 强制）
    operator            VARCHAR(64)   NOT NULL,             -- 登记人
    confirmer           VARCHAR(64),                        -- 用途确认人（勾选动作留痕）
    remark              VARCHAR(256),                       -- 备注（中文）
    idem_key            VARCHAR(80)   NOT NULL,             -- 登记幂等键（客户端生成 UUID）
    created_by          VARCHAR(16)   NOT NULL DEFAULT 'system',
    updated_by          VARCHAR(16)   NOT NULL DEFAULT 'system',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_fin_input_invoice_kind CHECK (invoice_kind IN ('SPECIAL','CUSTOMS','TOLL','PASSENGER','OTHER')),
    CONSTRAINT chk_fin_input_invoice_rate CHECK (tax_rate IN (0, 0.01, 0.03, 0.06, 0.13)),
    CONSTRAINT chk_fin_input_invoice_category CHECK (category IN ('SERVICE','PRODUCT','MEMBERSHIP')),
    CONSTRAINT chk_fin_input_invoice_purpose CHECK (purpose IN ('PENDING','DEDUCT','NO_DEDUCT','REFUND')),
    CONSTRAINT chk_fin_input_invoice_status CHECK (status IN ('UNCONFIRMED','CONFIRMED','DEDUCTED','TRANSFERRED_OUT','NON_DEDUCTIBLE')),
    CONSTRAINT chk_fin_input_invoice_reason CHECK (nondeduct_reason IS NULL OR nondeduct_reason IN ('WELFARE','LOSS_GOODS','LOSS_PRODUCT','LOSS_REAL_ESTATE','LOSS_CONSTRUCTION','LOAN_DAILY','OTHER')),
    CONSTRAINT chk_fin_input_invoice_amount CHECK (amount >= 0 AND net_amount >= 0 AND tax_amount >= 0),
    CONSTRAINT chk_fin_input_invoice_identity CHECK (amount = net_amount + tax_amount),
    CONSTRAINT chk_fin_input_invoice_transfer CHECK (transfer_out_amount >= 0)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_fin_input_invoice_register_no ON fin_input_invoice(register_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_fin_input_invoice_idem ON fin_input_invoice(idem_key);
-- 重复入账防控：同销方税号＋发票号码仅一条活跃票；NON_DEDUCTIBLE 终态不参与约束
CREATE UNIQUE INDEX IF NOT EXISTS uk_fin_input_invoice_dedup
    ON fin_input_invoice(seller_tax_no, invoice_no)
    WHERE status IN ('UNCONFIRMED','CONFIRMED','DEDUCTED','TRANSFERRED_OUT');
CREATE INDEX IF NOT EXISTS idx_fin_input_invoice_store_status ON fin_input_invoice(store_code, status);
CREATE INDEX IF NOT EXISTS idx_fin_input_invoice_period ON fin_input_invoice(period_id);
CREATE INDEX IF NOT EXISTS idx_fin_input_invoice_date ON fin_input_invoice(invoice_date);

CREATE TABLE IF NOT EXISTS fin_tax_period (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    period_type         VARCHAR(8)    NOT NULL,             -- MONTH 按月 / QUARTER 按季（演示环境默认按月）
    period              VARCHAR(16)   NOT NULL,             -- 期间标识：月 2026-08 / 季 2026-Q3
    period_start        DATE          NOT NULL,             -- 税款所属期起
    period_end          DATE          NOT NULL,             -- 税款所属期止
    deadline            DATE          NOT NULL,             -- 申报截止日（2026 年按税总办征科函〔2025〕64号顺延日历）
    status              VARCHAR(16)   NOT NULL DEFAULT 'OPEN', -- OPEN 未申报 / FILED 已申报 / LATE_FILED 逾期补申报 / AMENDED 更正申报 / CLOSED 归档
    output_amount       BIGINT        NOT NULL DEFAULT 0,   -- 申报快照：销项税额（分）
    input_amount        BIGINT        NOT NULL DEFAULT 0,   -- 申报快照：可抵扣进项税额（分）
    transfer_out_amount BIGINT        NOT NULL DEFAULT 0,   -- 申报快照：进项转出额（分）
    payable_amount      BIGINT        NOT NULL DEFAULT 0,   -- 申报快照：应纳税额（分）
    retained_amount     BIGINT        NOT NULL DEFAULT 0,   -- 申报快照：期末留抵（分，进项＞销项差额结转下期）
    filed_at            TIMESTAMPTZ,                        -- 申报动作时间
    filer               VARCHAR(64),                        -- 申报人
    remark              VARCHAR(256),                       -- 备注（中文，更正申报追加留痕）
    created_by          VARCHAR(16)   NOT NULL DEFAULT 'system',
    updated_by          VARCHAR(16)   NOT NULL DEFAULT 'system',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_fin_tax_period_type CHECK (period_type IN ('MONTH','QUARTER')),
    CONSTRAINT chk_fin_tax_period_status CHECK (status IN ('OPEN','FILED','LATE_FILED','AMENDED','CLOSED')),
    CONSTRAINT chk_fin_tax_period_range CHECK (period_end >= period_start),
    CONSTRAINT chk_fin_tax_period_amount CHECK (output_amount >= 0 AND input_amount >= 0
        AND transfer_out_amount >= 0 AND payable_amount >= 0 AND retained_amount >= 0)
);
-- 同类型同期间仅允许一条非归档记录（更正申报 AMENDED 仍唯一，CLOSED 归档后可再开新行）
CREATE UNIQUE INDEX IF NOT EXISTS uk_fin_tax_period_open
    ON fin_tax_period(period_type, period)
    WHERE status <> 'CLOSED';
CREATE INDEX IF NOT EXISTS idx_fin_tax_period_status ON fin_tax_period(status);
CREATE INDEX IF NOT EXISTS idx_fin_tax_period_range ON fin_tax_period(period_start, period_end);
