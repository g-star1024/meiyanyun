package com.meiyun.customer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * M3 客户域设置服务（M3-B1 / DESIGN-M3 §3 M3-18）。
 *
 * <p>读写模型：m3_settings 单行 id=1，settings JSONB 全量键；读时默认值合并
 * （DB 值覆盖 DEFAULTS，未来新增键未落库时按默认兜底）；写时白名单校验＋类型规范化＋
 * 差异比对，仅差异键落 {@link M3SettingsChangeLog}（action 人类可读描述，payload 前后快照）。
 *
 * <p>桥接（DESIGN L80 硬编码改读配置）：保存/复位触及 levelSource /
 * downgradeProtectionMonths / pointsMultiplier 时同步 {@link LevelRuleConfig} 单行 rule_id=1
 * （m3_settings 为主、level_rule_config 为从；levelSource→auto_upgrade/auto_downgrade：
 * AUTO=双开 / MANUAL=双关 / HYBRID=升开降关手动复核降级）。
 * txn-service RfmCalculator 沉睡 180 天口径跨服务，按 DESIGN L173 列移交不入本批。
 */
@Service
public class M3SettingsService {

    /** 单例行主键（V53 CHECK id = 1）。 */
    public static final int SINGLETON_ID = 1;

    /** 默认值：UI 五组 20 键（序与前端 m3settings store 活规格一致）＋后端专用键。 */
    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();
    static {
        // 脱敏规则
        DEFAULTS.put("maskPhone", true);
        DEFAULTS.put("maskIdCard", true);
        DEFAULTS.put("maskPhoneInExport", true);
        DEFAULTS.put("decryptRequiresApproval", true);
        DEFAULTS.put("decryptRetentionHours", 4);
        // 等级来源
        DEFAULTS.put("levelSource", "AUTO");
        DEFAULTS.put("levelCalcCycle", "MONTHLY");
        DEFAULTS.put("downgradeProtectionMonths", 3);
        DEFAULTS.put("pointsMultiplier", 1.0);
        // 标签自动化
        DEFAULTS.put("autoTagDormant", true);
        DEFAULTS.put("dormantDays", 90);
        DEFAULTS.put("autoTagHighValue", true);
        DEFAULTS.put("highValueThreshold", 50000);
        DEFAULTS.put("autoTagChurnRisk", true);
        // 隐私合规
        DEFAULTS.put("dataRetentionMonths", 36);
        DEFAULTS.put("allowCrossStoreShare", false);
        DEFAULTS.put("enableWatermark", true);
        DEFAULTS.put("emrLockDays", 30);
        // 跟进规则
        DEFAULTS.put("autoCreateFollowTask", true);
        DEFAULTS.put("complaintAutoTask", true);
        DEFAULTS.put("npsDetractorAutoTask", true);
        // 后端专用（UI 不绑定）：NPS 触达数（M3-12 回收率 = 评价数 / 触达数 口径供数）
        DEFAULTS.put("npsReachCount", 42);
    }

    /** 布尔键白名单（12 个）。 */
    private static final Set<String> BOOL_KEYS = Set.of(
            "maskPhone", "maskIdCard", "maskPhoneInExport", "decryptRequiresApproval",
            "autoTagDormant", "autoTagHighValue", "autoTagChurnRisk",
            "allowCrossStoreShare", "enableWatermark",
            "autoCreateFollowTask", "complaintAutoTask", "npsDetractorAutoTask");

    /** 整数键取值范围 {min, max}。 */
    private static final Map<String, long[]> INT_RANGES = Map.of(
            "decryptRetentionHours", new long[]{1, 72},
            "downgradeProtectionMonths", new long[]{0, 12},
            "dormantDays", new long[]{7, 365},
            "highValueThreshold", new long[]{1, 10_000_000},
            "dataRetentionMonths", new long[]{6, 120},
            "emrLockDays", new long[]{0, 365},
            "npsReachCount", new long[]{0, 100_000_000});

    /** 小数键取值范围 {min, max}。 */
    private static final Map<String, double[]> DOUBLE_RANGES = Map.of(
            "pointsMultiplier", new double[]{0.1, 10.0});

    private static final Set<String> LEVEL_SOURCES = Set.of("AUTO", "MANUAL", "HYBRID");
    private static final Set<String> LEVEL_CYCLES = Set.of("MONTHLY", "QUARTERLY");

    /** 「重置默认」复位键（照前端 m3settings store resetDefault 活规格 12 键）。 */
    private static final Set<String> RESET_KEYS = Set.of(
            "maskPhone", "maskIdCard", "maskPhoneInExport", "decryptRequiresApproval", "decryptRetentionHours",
            "levelSource", "downgradeProtectionMonths", "pointsMultiplier",
            "autoTagDormant", "dormantDays", "dataRetentionMonths", "enableWatermark");

    /** 键中文名（变更日志单键描述用）。 */
    private static final Map<String, String> KEY_LABEL = new LinkedHashMap<>();
    /** 数值键单位后缀（变更日志描述用）。 */
    private static final Map<String, String> KEY_UNIT = new LinkedHashMap<>();
    static {
        KEY_LABEL.put("maskPhone", "手机号脱敏");
        KEY_LABEL.put("maskIdCard", "身份证号脱敏");
        KEY_LABEL.put("maskPhoneInExport", "手机号导出脱敏");
        KEY_LABEL.put("decryptRequiresApproval", "解密需审批");
        KEY_LABEL.put("decryptRetentionHours", "解密留痕时长");
        KEY_LABEL.put("levelSource", "等级来源");
        KEY_LABEL.put("levelCalcCycle", "等级计算周期");
        KEY_LABEL.put("downgradeProtectionMonths", "降级保护期");
        KEY_LABEL.put("pointsMultiplier", "积分倍率");
        KEY_LABEL.put("autoTagDormant", "沉睡客户自动打标");
        KEY_LABEL.put("dormantDays", "沉睡阈值");
        KEY_LABEL.put("autoTagHighValue", "高价值客户自动打标");
        KEY_LABEL.put("highValueThreshold", "高价值阈值");
        KEY_LABEL.put("autoTagChurnRisk", "流失风险自动打标");
        KEY_LABEL.put("dataRetentionMonths", "数据保留时长");
        KEY_LABEL.put("allowCrossStoreShare", "跨店共享");
        KEY_LABEL.put("enableWatermark", "病历水印");
        KEY_LABEL.put("emrLockDays", "病历锁定期");
        KEY_LABEL.put("autoCreateFollowTask", "自动创建跟进任务");
        KEY_LABEL.put("complaintAutoTask", "客诉自动生成任务");
        KEY_LABEL.put("npsDetractorAutoTask", "NPS 贬损者自动任务");
        KEY_LABEL.put("npsReachCount", "NPS 触达数");
        KEY_UNIT.put("decryptRetentionHours", " 小时");
        KEY_UNIT.put("downgradeProtectionMonths", " 个月");
        KEY_UNIT.put("dormantDays", " 天");
        KEY_UNIT.put("highValueThreshold", " 元");
        KEY_UNIT.put("dataRetentionMonths", " 个月");
        KEY_UNIT.put("emrLockDays", " 天");
        KEY_UNIT.put("pointsMultiplier", " 倍");
        KEY_UNIT.put("npsReachCount", " 人");
    }

    /** 等级来源文案（变更日志描述用）。 */
    private static final Map<String, String> LEVEL_SOURCE_LABEL = Map.of(
            "AUTO", "自动升降级", "MANUAL", "仅手动调整", "HYBRID", "自动+手动复核");
    private static final Map<String, String> LEVEL_CYCLE_LABEL = Map.of(
            "MONTHLY", "每月", "QUARTERLY", "每季");

    private final M3SettingsRepository settingsRepo;
    private final M3SettingsChangeLogRepository logRepo;
    private final LevelRuleConfigRepository levelRuleRepo;

    public M3SettingsService(M3SettingsRepository settingsRepo, M3SettingsChangeLogRepository logRepo,
                             LevelRuleConfigRepository levelRuleRepo) {
        this.settingsRepo = settingsRepo;
        this.logRepo = logRepo;
        this.levelRuleRepo = levelRuleRepo;
    }

    /** 读全量设置（DEFAULTS 合并 DB 值；单例行不存在时纯默认）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> loadMerged() {
        Map<String, Object> merged = new LinkedHashMap<>(DEFAULTS);
        settingsRepo.findById(SINGLETON_ID).ifPresent(row -> {
            if (row.getSettings() != null) merged.putAll(row.getSettings());
        });
        return merged;
    }

    /** 默认值快照（播种器用；返回副本防外部篡改）。 */
    public static Map<String, Object> defaultSettings() {
        return new LinkedHashMap<>(DEFAULTS);
    }

    /** 单例行元数据（GET 返回 updatedAt/updatedBy 用；行不存在返 null）。 */
    @Transactional(readOnly = true)
    public M3Settings currentRowOrNull() {
        return settingsRepo.findById(SINGLETON_ID).orElse(null);
    }

    /** NPS 触达数（M3-12 回收率口径供数；NpsService 专用）。 */
    @Transactional(readOnly = true)
    public int getReachCount() {
        Object v = loadMerged().get("npsReachCount");
        return v instanceof Number n ? n.intValue() : 42;
    }

    /** 变更记录卡数据源（近 50 条倒序）。 */
    @Transactional(readOnly = true)
    public List<M3SettingsChangeLog> listLogs() {
        return logRepo.findTop50ByOrderByCreatedAtDesc();
    }

    /** 保存结果：settings=保存后全量，changedKeys=本次差异键，saved=是否真实落库（无差异幂等返回 false）。 */
    public record SaveResult(Map<String, Object> settings, List<String> changedKeys, boolean saved) {}

    /**
     * 保存设置：白名单校验＋类型规范化＋差异比对；有差异才落库＋写变更日志＋桥接 level_rule_config。
     * 无差异幂等返回（saved=false），不重复落日志。
     */
    @Transactional
    public synchronized SaveResult saveSettings(Map<String, Object> req, String actor) {
        if (req == null || req.isEmpty()) throw new CustomerService.BadReq("设置内容不能为空");
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : req.entrySet()) {
            normalized.put(e.getKey(), validate(e.getKey(), e.getValue()));
        }
        Map<String, Object> current = loadMerged();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : normalized.entrySet()) {
            if (!Objects.equals(current.get(e.getKey()), e.getValue())) {
                before.put(e.getKey(), current.get(e.getKey()));
                after.put(e.getKey(), e.getValue());
            }
        }
        if (after.isEmpty()) {
            return new SaveResult(current, List.of(), false);
        }
        Map<String, Object> merged = new LinkedHashMap<>(current);
        merged.putAll(after);
        persistRow(merged, actor);
        appendLog(describe(after), actor, before, after);
        if (after.containsKey("levelSource") || after.containsKey("downgradeProtectionMonths")
                || after.containsKey("pointsMultiplier")) {
            syncLevelRuleConfig(merged);
        }
        return new SaveResult(merged, new ArrayList<>(after.keySet()), true);
    }

    /** 重置默认：仅复位 RESET_KEYS 12 键（照前端活规格），其余键保留现值；已是默认则幂等不落日志。 */
    @Transactional
    public synchronized SaveResult resetDefaults(String actor) {
        Map<String, Object> current = loadMerged();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (String k : RESET_KEYS) {
            Object def = DEFAULTS.get(k);
            if (!Objects.equals(current.get(k), def)) {
                before.put(k, current.get(k));
                after.put(k, def);
            }
        }
        if (after.isEmpty()) {
            return new SaveResult(current, List.of(), false);
        }
        Map<String, Object> merged = new LinkedHashMap<>(current);
        merged.putAll(after);
        persistRow(merged, actor);
        appendLog("恢复默认设置", actor, before, after);
        if (after.containsKey("levelSource") || after.containsKey("downgradeProtectionMonths")
                || after.containsKey("pointsMultiplier")) {
            syncLevelRuleConfig(merged);
        }
        return new SaveResult(merged, new ArrayList<>(after.keySet()), true);
    }

    // ---- 内部 ----

    private void persistRow(Map<String, Object> merged, String actor) {
        M3Settings row = settingsRepo.findById(SINGLETON_ID).orElseGet(() -> {
            M3Settings s = new M3Settings();
            s.setId(SINGLETON_ID);
            return s;
        });
        row.setSettings(merged);
        row.setUpdatedBy(actor == null || actor.isBlank() ? "system" : actor);
        settingsRepo.save(row);
    }

    private void appendLog(String action, String actor, Map<String, Object> before, Map<String, Object> after) {
        M3SettingsChangeLog log = new M3SettingsChangeLog();
        log.setAction(action);
        log.setActor(actor == null || actor.isBlank() ? "system" : actor);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("before", before);
        payload.put("after", after);
        log.setPayload(payload);
        logRepo.save(log);
    }

    /** 桥接 level_rule_config（m3_settings 为主）：levelSource→双开关映射，保护期/倍率直写。 */
    private void syncLevelRuleConfig(Map<String, Object> merged) {
        LevelRuleConfig cfg = levelRuleRepo.findById(1).orElseGet(() -> {
            LevelRuleConfig c = new LevelRuleConfig();
            c.setRuleId(1);
            return c;
        });
        String ls = String.valueOf(merged.get("levelSource"));
        cfg.setAutoUpgrade(!"MANUAL".equals(ls));
        cfg.setAutoDowngrade("AUTO".equals(ls));
        cfg.setDowngradeProtectMonths(((Number) merged.get("downgradeProtectionMonths")).intValue());
        cfg.setPointsMultiplier(BigDecimal.valueOf(((Number) merged.get("pointsMultiplier")).doubleValue()));
        cfg.setUpdatedAt(OffsetDateTime.now());
        levelRuleRepo.save(cfg);
    }

    /** 变更日志动作描述：单键精确（如「沉睡阈值调整为 90 天」/「开启手机号导出脱敏」），多键汇总。 */
    private String describe(Map<String, Object> after) {
        if (after.size() != 1) return "保存客户域设置";
        Map.Entry<String, Object> e = after.entrySet().iterator().next();
        String key = e.getKey();
        String label = KEY_LABEL.getOrDefault(key, key);
        Object v = e.getValue();
        if (v instanceof Boolean b) return (b ? "开启" : "关闭") + label;
        if ("levelSource".equals(key)) return label + "调整为 " + LEVEL_SOURCE_LABEL.getOrDefault(String.valueOf(v), String.valueOf(v));
        if ("levelCalcCycle".equals(key)) return label + "调整为 " + LEVEL_CYCLE_LABEL.getOrDefault(String.valueOf(v), String.valueOf(v));
        return label + "调整为 " + fmtNumber(v) + KEY_UNIT.getOrDefault(key, "");
    }

    /** 数值展示：去尾随零（1.0→1，1.5→1.5）。 */
    private static String fmtNumber(Object v) {
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    /** 单键校验＋类型规范化：未知键/类型错/越界/词表外一律中文 BadReq。 */
    private static Object validate(String key, Object value) {
        if (!DEFAULTS.containsKey(key)) throw new CustomerService.BadReq("未知设置键：" + key);
        if (value == null) throw new CustomerService.BadReq("设置值不能为空：" + key);
        if (BOOL_KEYS.contains(key)) {
            if (!(value instanceof Boolean)) throw new CustomerService.BadReq("设置值须为布尔：" + key);
            return value;
        }
        if (INT_RANGES.containsKey(key)) {
            long v = toLong(key, value);
            long[] range = INT_RANGES.get(key);
            if (v < range[0] || v > range[1]) {
                throw new CustomerService.BadReq("设置值超出范围（" + range[0] + "-" + range[1] + "）：" + key);
            }
            return (int) v;
        }
        if (DOUBLE_RANGES.containsKey(key)) {
            double v = toDouble(key, value);
            double[] range = DOUBLE_RANGES.get(key);
            if (v < range[0] || v > range[1]) {
                throw new CustomerService.BadReq("设置值超出范围（" + range[0] + "-" + range[1] + "）：" + key);
            }
            return v;
        }
        if ("levelSource".equals(key)) {
            String v = String.valueOf(value).trim();
            if (!LEVEL_SOURCES.contains(v)) throw new CustomerService.BadReq("等级来源取值非法：AUTO / MANUAL / HYBRID");
            return v;
        }
        if ("levelCalcCycle".equals(key)) {
            String v = String.valueOf(value).trim();
            if (!LEVEL_CYCLES.contains(v)) throw new CustomerService.BadReq("等级计算周期取值非法：MONTHLY / QUARTERLY");
            return v;
        }
        throw new CustomerService.BadReq("未知设置键：" + key);
    }

    private static long toLong(String key, Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.floor(d)) throw new CustomerService.BadReq("设置值须为整数：" + key);
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new CustomerService.BadReq("设置值须为整数：" + key);
        }
    }

    private static double toDouble(String key, Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new CustomerService.BadReq("设置值须为数字：" + key);
        }
    }
}
