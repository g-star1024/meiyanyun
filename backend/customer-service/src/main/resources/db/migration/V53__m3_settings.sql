-- ============================================================
-- V53 M3 客户域设置单例表＋变更日志表（M3-B1 / DESIGN-M3 §3 M3-18）
-- 双表：m3_settings（单行 id=1 单例，settings JSONB 全量键）
--      m3_settings_change_log（变更日志，谁改了什么何时改）
-- 说明：flyway_schema_history 全库共享，版本号全局递增（全局最大 V52 后取 V53）。
-- 幂等可重入：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS。
-- 单例约束：m3_settings 只允许 id=1 一行（CHECK id = 1），不建物理外键。
-- settings JSONB 键域（20 个 UI 键＋后端专用键）：
--   脱敏 maskPhone/maskIdCard/maskPhoneInExport/decryptRequiresApproval/decryptRetentionHours
--   等级 levelSource(AUTO|MANUAL|HYBRID)/levelCalcCycle/downgradeProtectionMonths/pointsMultiplier
--   标签 autoTagDormant/dormantDays/autoTagHighValue/highValueThreshold/autoTagChurnRisk
--   隐私 dataRetentionMonths/allowCrossStoreShare/enableWatermark/emrLockDays
--   跟进 autoCreateFollowTask/complaintAutoTask/npsDetractorAutoTask
--   后端专用 npsReachCount（NPS 触达数，M3-12 回收率口径供数，UI 不绑定）
-- 桥接约定（DESIGN L80 硬编码改读配置）：保存设置时由服务层同步 level_rule_config
--   （levelSource→auto_upgrade/auto_downgrade：AUTO=双开/MANUAL=双关/HYBRID=升开降关；
--     downgradeProtectionMonths→downgrade_protect_months；pointsMultiplier→points_multiplier），
--   m3_settings 为主、level_rule_config 为从；txn-service RfmCalculator 沉睡 180 天口径跨服务，列移交。
-- ddl-auto=update 双轨（application.yml）：Hibernate 依实体映射补列，本 DDL 为准绳。
-- ============================================================

CREATE TABLE IF NOT EXISTS m3_settings (
    id         SMALLINT    PRIMARY KEY,
    settings   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'system',
    CONSTRAINT chk_m3_settings_singleton CHECK (id = 1)
);

COMMENT ON TABLE  m3_settings IS 'M3 客户域设置单例（M3-B1 / DESIGN-M3 §3 M3-18）：仅 id=1 一行，settings JSONB 存全量配置键；UI 五组 20 键＋后端专用键（如 npsReachCount）；保存时桥接同步 level_rule_config（主从）';
COMMENT ON COLUMN m3_settings.id IS '单例行主键：恒为 1（CHECK id = 1 保证全表仅一行）';
COMMENT ON COLUMN m3_settings.settings IS '设置全量 JSON：五组 20 个 UI 键＋后端专用键；读写整体替换，服务层负责缺省值合并';
COMMENT ON COLUMN m3_settings.updated_at IS '最近保存时刻（TIMESTAMPTZ）';
COMMENT ON COLUMN m3_settings.updated_by IS '最近保存操作者（DataScope.currentActor()）';

CREATE TABLE IF NOT EXISTS m3_settings_change_log (
    id         BIGSERIAL    PRIMARY KEY,
    action     VARCHAR(128) NOT NULL,
    actor      VARCHAR(64)  NOT NULL DEFAULT 'system',
    payload    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  m3_settings_change_log IS 'M3 设置变更日志（M3-B1）：每次保存/复位追加一行，action 人类可读描述（如「沉睡阈值调整为 90 天」），payload 存变更前后键值快照；M3-18 变更记录卡直读';
COMMENT ON COLUMN m3_settings_change_log.action IS '变更动作描述（人类可读，展示于 M3-18 变更记录卡）';
COMMENT ON COLUMN m3_settings_change_log.actor IS '操作者（DataScope.currentActor()，含角色后缀如「陈野（区域经理）」由前端拼装则此列存原始工号/姓名）';
COMMENT ON COLUMN m3_settings_change_log.payload IS '变更快照 JSON：{before:{...}, after:{...}} 仅含差异键';
COMMENT ON COLUMN m3_settings_change_log.created_at IS '变更发生时刻（TIMESTAMPTZ）';

CREATE INDEX IF NOT EXISTS idx_m3_settings_change_log_at ON m3_settings_change_log (created_at DESC);
