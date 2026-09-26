-- ============================================================
-- V56  segment_def  AI 客户分群定义表（M3-B3 / DESIGN-M3 §3 D2 M3-14、D3-3）
--
-- 口径（与全站 flyway 约定一致，照 V54 文体）：
--   1) flyway 版本号全局递增（customer-service 最新 V54 → 本脚本 V56）；
--   2) 幂等可重放：CREATE TABLE/INDEX 全部 IF NOT EXISTS，种子 INSERT ... WHERE NOT EXISTS；
--   3) 逻辑引用零外键（不引用 customer/customer_tag 等表，跨表一致性由应用层保证）；
--   4) ddl-auto=update 双轨：本脚本为权威 DDL，Hibernate 仅补齐实体派生差异；
--   5) 列级 COMMENT 全量，CHECK 约束枚举白名单。
--
-- 业务契约：
--   - type 六值（前端 SegmentType 全站统一）：HIGH_POTENTIAL 高潜 / DORMANT 沉睡 /
--     PRICE_SENSITIVE 价格敏感 / HIGH_VALUE 高价值 / CHURN_RISK 流失风险 / NEW 新客；
--   - ai_status：RULE 规则分群（用户新建/种子） / AI 建议分群（画像 sync-profile 落库，D3-3）；
--   - conditions JSONB 结构化条件数组，元素 {kind,...,label}，kind 白名单（RULE 引擎六 kind）：
--       DORMANT_DAYS   {days}            沉睡（customer.status='沉睡'，days 为文案口径）
--       VISIT_IN_DAYS  {days,times}      到店次数 ≥ times（累计近似，days 为文案口径）
--       SPEND_RANGE    {min,max}         累计消费区间（total_spend）
--       LEVEL_GTE      {level}           等级 ≥（普通<银卡<金卡<钻石<黑卡）
--       TAG_ANY        {tags[]}          带任一客户标签（customer_tag_rel join customer_tag）
--       CREATED_IN_DAYS {days}           建档 ≤ days 天（新客）
--     label 为人读文案（视图直接展示），rule_summary = label 联排；
--   - customer_count / share_pct 为 refresh 重算快照（RULE 引擎实时扫描回写）；
--   - store_code NULL = 全连锁共享；uk (name, COALESCE(store_code,'*')) 同名分群幂等防重；
--   - client_token 创建幂等键（前端重放安全）；source_profile_id 溯源 ai 画像（sync-profile）。
-- ============================================================

CREATE TABLE IF NOT EXISTS segment_def (
    id               BIGSERIAL PRIMARY KEY,
    segment_no       VARCHAR(16)  NOT NULL,
    name             VARCHAR(64)  NOT NULL,
    type             VARCHAR(16)  NOT NULL,
    ai_status        VARCHAR(8)   NOT NULL DEFAULT 'RULE',
    conditions       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    rule_summary     VARCHAR(200) NOT NULL DEFAULT '',
    customer_count   INTEGER      NOT NULL DEFAULT 0,
    share_pct        NUMERIC(5,2) NOT NULL DEFAULT 0,
    ai_suggestion    TEXT,
    store_code       VARCHAR(16),
    client_token     VARCHAR(64),
    source_profile_id BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_segment_def_type CHECK (type IN
        ('HIGH_POTENTIAL','DORMANT','PRICE_SENSITIVE','HIGH_VALUE','CHURN_RISK','NEW')),
    CONSTRAINT ck_segment_def_ai_status CHECK (ai_status IN ('AI','RULE'))
);

COMMENT ON TABLE  segment_def IS 'AI 客户分群定义（M3-14）：RULE 规则分群 + AI 建议分群（画像 sync-profile 落库，D3-3）';
COMMENT ON COLUMN segment_def.segment_no IS '分群编号 SG####（应用层号池，基于库内最大号递增）';
COMMENT ON COLUMN segment_def.name IS '分群名称（同名幂等：uk (name, COALESCE(store_code,''*''))）';
COMMENT ON COLUMN segment_def.type IS '分群类型六值：HIGH_POTENTIAL/DORMANT/PRICE_SENSITIVE/HIGH_VALUE/CHURN_RISK/NEW';
COMMENT ON COLUMN segment_def.ai_status IS '来源：RULE 规则分群 / AI 建议分群（画像应用到分群落库）';
COMMENT ON COLUMN segment_def.conditions IS '结构化条件 JSONB 数组 [{kind,...,label}]，kind：DORMANT_DAYS/VISIT_IN_DAYS/SPEND_RANGE/LEVEL_GTE/TAG_ANY/CREATED_IN_DAYS';
COMMENT ON COLUMN segment_def.rule_summary IS '人读规则摘要（conditions.label 联排）';
COMMENT ON COLUMN segment_def.customer_count IS '命中客户数快照（refresh RULE 引擎实时扫描回写）';
COMMENT ON COLUMN segment_def.share_pct IS '占全部在档客户比例快照（0-100）';
COMMENT ON COLUMN segment_def.ai_suggestion IS 'AI 运营建议文案（AI 分群）';
COMMENT ON COLUMN segment_def.store_code IS '门店范围；NULL=全连锁共享';
COMMENT ON COLUMN segment_def.client_token IS '创建幂等键（前端重放安全，撞键直返已建行）';
COMMENT ON COLUMN segment_def.source_profile_id IS '来源 ai 画像 profileId（sync-profile 溯源；RULE 分群为 NULL）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_segment_def_no ON segment_def (segment_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_segment_def_name_store ON segment_def (name, COALESCE(store_code, '*'));
CREATE INDEX IF NOT EXISTS idx_segment_def_type ON segment_def (type);
-- client_token 幂等先查：部分唯一索引（非空才唯一，撞键并发兜底）
CREATE UNIQUE INDEX IF NOT EXISTS uk_segment_def_client_token ON segment_def (client_token) WHERE client_token IS NOT NULL;

-- 种子三行 RULE 分群（幂等；保证分群页首屏非空，验收后业务可自行增删）
INSERT INTO segment_def (segment_no, name, type, ai_status, conditions, rule_summary, ai_suggestion)
SELECT 'SG0001', '高价值 VIP', 'HIGH_VALUE', 'RULE',
    '[{"kind":"SPEND_RANGE","min":30000,"max":99999999,"label":"累计消费 > 30000"},{"kind":"LEVEL_GTE","level":"金卡","label":"等级 ≥ 金卡"}]'::jsonb,
    '累计消费 > 30000、等级 ≥ 金卡',
    '建议店长 1v1 维护，邀请线下私享会，推荐抗衰年卡。'
WHERE NOT EXISTS (SELECT 1 FROM segment_def WHERE segment_no = 'SG0001');

INSERT INTO segment_def (segment_no, name, type, ai_status, conditions, rule_summary, ai_suggestion)
SELECT 'SG0002', '沉睡 60 天客户', 'DORMANT', 'RULE',
    '[{"kind":"DORMANT_DAYS","days":60,"label":"60 天未到店"}]'::jsonb,
    '60 天未到店',
    '建议推送专属唤醒券 + 老客回归礼。'
WHERE NOT EXISTS (SELECT 1 FROM segment_def WHERE segment_no = 'SG0002');

INSERT INTO segment_def (segment_no, name, type, ai_status, conditions, rule_summary, ai_suggestion)
SELECT 'SG0003', '本月新客', 'NEW', 'RULE',
    '[{"kind":"CREATED_IN_DAYS","days":31,"label":"本月首次到店"}]'::jsonb,
    '本月首次到店',
    '建议新客首周回访 + 护理后注意事项触达。'
WHERE NOT EXISTS (SELECT 1 FROM segment_def WHERE segment_no = 'SG0003');
