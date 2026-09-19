package com.meiyun.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.AuthInterceptor;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预收合规监控扫描（B63 卡3 L85，V38 prepay_monitor_rule / prepay_monitor_event）。
 *
 * <p>每日 02:20（北京时区，与 customer 合规巡检 02:00 错峰）由 {@link PrepayMonitorJob} 触发，
 * 亦可由内部端点 {@code POST /api/finance/internal/prepay-monitor/run}（X-Internal-Token）手动触发。
 * 三类规则：
 * <ul>
 *   <li>DEPOSIT_AGE 充值沉淀账龄（unit=DAY）：finance 本地 fund_entry 按门店聚合 RF-DEPOSIT 净沉淀
 *       （IN 充值 − OUT 核销/退卡冲回），净沉淀为正且最早一笔充值距今 ≥ 阈值天命中；</li>
 *   <li>REFUND_PENDING 退款待核销金额（unit=FEN）：txn finance-flows 全量已退款流水按店聚合
 *       （银行/渠道回单未接入，RF-REFUND 台账 reconciled 恒 false，故全量计待核销），超阈值命中；</li>
 *   <li>DORMANT_CARD 沉睡卡沉淀（unit=FEN）：customer card-balances 投影中卡状态为休眠
 *       （中文状态非「在用/退卡中」，即「已退卡/已用完」口径，与前端 DORMANT 映射一致）
 *       且 balance&gt;0 的卡按店聚合储值本金（不含赠金，对齐前端 dormantAmount），超阈值命中。</li>
 * </ul>
 *
 * <p><b>首启安全三层</b>：V38 纯 DDL 零规则行 + 规则行级 enabled 默认 false + store_code 非空为
 * 灰度白名单（NULL=全店）；另以 COMPLIANCE: 站内信幂等键兜底。
 *
 * <p><b>事件生命周期</b>：命中门店若无 OPEN 事件则落一条 OPEN（部分唯一索引 uk_..._open 兜底
 * 同规则同店仅一条 OPEN），仅新发 OPEN 外呼 txn compliance-alert（软降级，不回滚事件）；
 * 本轮不再命中的门店，其存量 OPEN 批量回转 RESOLVED，之后可再次开新事件（轮次计入 idem_key）。
 *
 * <p><b>审计</b>：每轮（含 0 命中 / 0 启用规则）落 COMPLIANCE/PREPAY_MONITOR 审计一条，
 * 证明巡检已执行。跨域取数沿用 {@link FinanceAggregationService} 的系统身份与降级口径。
 */
@Service
public class PrepayMonitorService {

    private static final Logger log = LoggerFactory.getLogger(PrepayMonitorService.class);

    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String ACTOR = "SYSTEM";
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_RESOLVED = "RESOLVED";

    private static final String TYPE_DEPOSIT_AGE = "DEPOSIT_AGE";
    private static final String TYPE_REFUND_PENDING = "REFUND_PENDING";
    private static final String TYPE_DORMANT_CARD = "DORMANT_CARD";

    /** 站内信前端落点：M6-10 预收款监管页。 */
    private static final String ALERT_LINK = "/m6-prepay";
    private static final int TITLE_MAX = 128;
    private static final int CONTENT_MAX = 500;
    private static final int BIZ_REF_MAX = 32;
    private static final int LIST_LIMIT = 20;

    private static final DateTimeFormatter BIZ_REF_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final FinanceAggregationService aggregation;
    private final FundEntryRepository fundEntryRepo;
    private final PrepayMonitorRuleRepository ruleRepo;
    private final PrepayMonitorEventRepository eventRepo;
    private final FinanceAuditRecorder audit;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${txn.service.url:http://127.0.0.1:8083}")
    private String txnBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public PrepayMonitorService(FinanceAggregationService aggregation,
                                FundEntryRepository fundEntryRepo,
                                PrepayMonitorRuleRepository ruleRepo,
                                PrepayMonitorEventRepository eventRepo,
                                FinanceAuditRecorder audit,
                                RestTemplate restTemplate) {
        this.aggregation = aggregation;
        this.fundEntryRepo = fundEntryRepo;
        this.ruleRepo = ruleRepo;
        this.eventRepo = eventRepo;
        this.audit = audit;
        this.restTemplate = restTemplate;
    }

    /** 单轮扫描结果（供内部触发端点回显与 Job 日志）。stores 为跨规则命中店次（同一店被多规则命中计多次）。 */
    public record ScanResult(int rules, int stores, int eventsFired, int alertsSent, int resolved) {
    }

    /**
     * 执行一轮全量扫描。无外层事务：事件保存/回转各自即时提交，外呼站内信置于持久化之后，
     * txn 不可用仅软降级不回滚事件（参照 customer ComplianceInspectionJob 范式）。
     */
    public ScanResult runOnce() {
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate bizDate = now.atZoneSameInstant(BIZ_ZONE).toLocalDate();

        List<PrepayMonitorRule> rules = ruleRepo.findByEnabledTrueOrderByCodeAsc();

        // 跨域原始数据一次性取全店（fetchFlows/fetchCards 内部已对被调方故障降级空集合）
        Map<String, Object> flows = aggregation.fetchFlows(null, null, null);
        Map<String, Long> refundFenByStore = aggregateRefunds(flows);
        List<Map<String, Object>> cards = aggregation.fetchCards(null);
        Map<String, Long> dormantFenByStore = aggregateDormantCards(cards);

        int hitStores = 0;
        int eventsFired = 0;
        int alertsSent = 0;
        int resolved = 0;
        List<Map<String, Object>> firedLog = new ArrayList<>();

        // 账龄投影按「最宽账龄阈值」只扫一次：阈值天数越小 cutoff 越近（越大），小阈值命中集合
        // 是大阈值的超集，各规则再以自身阈值二次过滤最早充值时间即可，无需逐规则查库。
        List<FundEntryRepository.DepositAgeRow> depositRows = List.of();
        long maxAgeDays = rules.stream()
                .filter(r -> TYPE_DEPOSIT_AGE.equals(r.getType()))
                .mapToLong(r -> r.getThresholdValue() == null ? 0L : r.getThresholdValue())
                .max().orElse(0L);
        if (maxAgeDays > 0L) {
            OffsetDateTime cutoff = bizDate.minusDays(maxAgeDays).atStartOfDay(BIZ_ZONE).toOffsetDateTime();
            depositRows = fundEntryRepo.depositAgeScan(cutoff);
        }

        for (PrepayMonitorRule rule : rules) {
            try {
                // 门店 -> 命中金额（分）；账龄类金额为净沉淀，其余为店聚合超额金额
                Map<String, Long> hits = evaluate(rule, bizDate, depositRows,
                        refundFenByStore, dormantFenByStore);
                if (hits.isEmpty()) {
                    resolved += resolveRule(rule, Set.of());
                    continue;
                }

                // 白名单收敛：store_code 非空时仅该门店生效
                Map<String, Long> scoped = new LinkedHashMap<>();
                hits.forEach((sc, amt) -> {
                    if (rule.getStoreCode() == null || rule.getStoreCode().isBlank()
                            || rule.getStoreCode().equals(sc)) {
                        scoped.put(sc, amt);
                    }
                });

                resolved += resolveRule(rule, scoped.keySet());

                // 店名仅对本轮需发事件的门店解析（批量一次）
                Map<String, String> names = aggregation.resolveStoreNames(new ArrayList<>(scoped.keySet()));

                for (Map.Entry<String, Long> hit : scoped.entrySet()) {
                    String storeCode = hit.getKey();
                    long amountFen = hit.getValue();
                    hitStores++;
                    if (eventRepo.findFirstByRuleCodeAndStoreCodeAndStatus(
                            rule.getCode(), storeCode, STATUS_OPEN).isPresent()) {
                        continue;
                    }
                    PrepayMonitorEvent event = openEvent(rule, storeCode, amountFen, names, now, bizDate);
                    eventRepo.save(event);
                    eventsFired++;
                    firedLog.add(Map.of("ruleCode", rule.getCode(), "storeCode", storeCode,
                            "amountFen", amountFen, "type", rule.getType()));
                    if (sendAlert(rule, event)) {
                        alertsSent++;
                    }
                }
            } catch (Exception e) {
                // 单规则异常不阻断其余规则与审计落库，下轮自愈
                log.warn("预收监控规则扫描失败 rule={} type={} : {}",
                        rule.getCode(), rule.getType(), e.getMessage());
            }
        }

        audit(bizDate, now, rules.size(), hitStores, eventsFired, alertsSent, resolved, firedLog);
        log.info("预收合规监控完成：启用规则={} 命中店次={} 新事件={} 站内信={} 回转={}（业务日 {}）",
                rules.size(), hitStores, eventsFired, alertsSent, resolved, bizDate);
        return new ScanResult(rules.size(), hitStores, eventsFired, alertsSent, resolved);
    }

    // ==================== 监管页读模型（overview） ====================

    /**
     * 监管页预警读模型：仅返回当前登录人数据域内的<b>未决（OPEN）规则事件</b>，作为前端
     * 三告警（退款待核销 / 沉睡沉淀 / 勾稽一致）之外的<b>真实规则事件补充</b>。
     *
     * <p>门店收敛与台账/卡余额同源：无上下文/GROUP/BRAND/REGION 空集全量；REGION 非空限其门店集；
     * STORE/SELF 限本门店（storeCode 异常时返回空）。4 KPI / 5 checks / 三告警的派生口径仍在前端，
     * 本模型不重复供给，避免双源数字漂移。金额由「分」转「元」，对齐前端 PrepayAlert.amount。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        List<PrepayMonitorEvent> opens = openEventsInScope();
        List<String> storeCodes = opens.stream()
                .map(PrepayMonitorEvent::getStoreCode)
                .filter(sc -> sc != null && !sc.isBlank())
                .distinct()
                .toList();
        Map<String, String> names = aggregation.resolveStoreNames(new ArrayList<>(storeCodes));

        List<Map<String, Object>> alerts = opens.stream()
                .limit(LIST_LIMIT)
                .map(e -> eventAlertView(e, names.getOrDefault(e.getStoreCode(), e.getStoreCode())))
                .toList();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("generatedAt", OffsetDateTime.now().toString());
        view.put("openCount", opens.size());
        view.put("alerts", alerts);
        return view;
    }

    /** 按当前登录人数据域查询全部 OPEN 事件（触发时间倒序）；STORE/SELF 异常账号返回空集。 */
    private List<PrepayMonitorEvent> openEventsInScope() {
        Sort sort = Sort.by(Sort.Order.desc("firedAt"), Sort.Order.desc("eventId"));
        LoginUser u = DataScope.current();
        if (u == null || u.isSuper()
                || DataScope.SCOPE_GROUP.equals(u.scope()) || DataScope.SCOPE_BRAND.equals(u.scope())) {
            return eventRepo.findByStatus(STATUS_OPEN, sort);
        }
        if (DataScope.SCOPE_REGION.equals(u.scope())) {
            List<String> stores = u.stores();
            if (stores == null || stores.isEmpty()) {
                return eventRepo.findByStatus(STATUS_OPEN, sort);
            }
            return eventRepo.findByStatusAndStoreCodeIn(STATUS_OPEN, stores, sort);
        }
        // STORE / SELF：绑定本门店；storeCode 异常（空）→ 不见任何数据
        if (u.storeCode() == null || u.storeCode().isBlank()) {
            return List.of();
        }
        return eventRepo.findByStatusAndStoreCodeIn(STATUS_OPEN, List.of(u.storeCode()), sort);
    }

    /** OPEN 事件 → 前端 PrepayAlert 契约（id/level/type/desc/amount/at），另附 ruleCode/storeCode 供溯源。 */
    private Map<String, Object> eventAlertView(PrepayMonitorEvent e, String storeName) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", String.valueOf(e.getEventId()));
        a.put("ruleCode", e.getRuleCode());
        a.put("level", e.getLevel());
        a.put("type", typeLabel(e.getType()));
        a.put("desc", e.getTitle());
        a.put("storeCode", e.getStoreCode());
        a.put("storeName", storeName);
        a.put("amount", e.getAmountFen() / 100.0);
        a.put("at", e.getFiredAt() == null ? null : e.getFiredAt().toString());
        return a;
    }

    private static String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case TYPE_DEPOSIT_AGE -> "充值沉淀超账龄";
            case TYPE_REFUND_PENDING -> "退款待核销超限";
            case TYPE_DORMANT_CARD -> "沉睡卡沉淀超限";
            default -> "预收合规告警";
        };
    }

    // ==================== 规则管理（仅后端端点，本卡不接管理 UI） ====================

    private static final Set<String> RULE_TYPES =
            Set.of(TYPE_DEPOSIT_AGE, TYPE_REFUND_PENDING, TYPE_DORMANT_CARD);
    private static final Set<String> RULE_LEVELS = Set.of("HIGH", "MEDIUM", "LOW");

    /** 规则列表：store_code=NULL 全局规则对认证用户可见；门店规则逐行 canReadStore 收敛。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRules() {
        List<PrepayMonitorRule> visible = ruleRepo.findAll(Sort.by(Sort.Order.asc("code"))).stream()
                .filter(r -> r.getStoreCode() == null || r.getStoreCode().isBlank()
                        || DataScope.canReadStore(r.getStoreCode()))
                .toList();
        List<String> storeCodes = visible.stream()
                .map(PrepayMonitorRule::getStoreCode)
                .filter(sc -> sc != null && !sc.isBlank())
                .distinct()
                .toList();
        Map<String, String> names = aggregation.resolveStoreNames(new ArrayList<>(storeCodes));
        return visible.stream().map(r -> ruleView(r, names)).toList();
    }

    /** 规则详情：不存在 / 越权（门店规则不在数据域）统一 404，不泄露存在性。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getRule(String code) {
        PrepayMonitorRule rule = ruleRepo.findById(normalizeCode(code))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "预收监控规则不存在：code=" + code));
        if (rule.getStoreCode() != null && !rule.getStoreCode().isBlank()
                && !DataScope.canReadStore(rule.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预收监控规则不存在或无权查看");
        }
        Map<String, String> names = rule.getStoreCode() == null
                ? Map.of()
                : aggregation.resolveStoreNames(List.of(rule.getStoreCode()));
        return ruleView(rule, names);
    }

    /** 新建规则：四件套（校验/审计/门店域断言/中文错误）；code 重复 409。 */
    @Transactional
    public Map<String, Object> createRule(Map<String, Object> body, String actor) {
        String code = normalizeCode(body.get("code") == null ? null : String.valueOf(body.get("code")));
        if (code.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "规则编码 code 不能为空");
        }
        if (code.length() > 16) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "规则编码 code 最长 16 位");
        }
        if (ruleRepo.existsById(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "规则编码已存在：" + code);
        }
        PrepayMonitorRule rule = new PrepayMonitorRule();
        rule.setCode(code);
        applyMutableFields(rule, body);
        String operator = actor == null || actor.isBlank() ? "system" : actor;
        rule.setCreatedBy(operator);
        rule.setUpdatedBy(operator);
        ruleRepo.save(rule);
        ruleAudit("PREPAY_RULE_CREATE", rule, operator);
        log.info("预收监控规则新建 code={} type={} store={} enabled={} actor={}",
                rule.getCode(), rule.getType(), rule.getStoreCode(), rule.isEnabled(), operator);
        return ruleView(rule, storeNameMap(rule));
    }

    /** 更新规则：code/type 不可变（与 body 不一致 422）；不存在 / 越权统一 404。 */
    @Transactional
    public Map<String, Object> updateRule(String code, Map<String, Object> body, String actor) {
        String useCode = normalizeCode(code);
        PrepayMonitorRule rule = ruleRepo.findById(useCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "预收监控规则不存在：code=" + code));
        if (rule.getStoreCode() != null && !rule.getStoreCode().isBlank()
                && !DataScope.canReadStore(rule.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预收监控规则不存在或无权修改");
        }
        Object bodyType = body.get("type");
        if (bodyType != null && !String.valueOf(bodyType).isBlank()
                && !String.valueOf(bodyType).trim().equals(rule.getType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "规则类型 type 不可变（现存 " + rule.getType() + "）；如需变更请新建规则");
        }
        applyMutableFields(rule, body);
        String operator = actor == null || actor.isBlank() ? "system" : actor;
        rule.setUpdatedBy(operator);
        ruleRepo.save(rule);
        ruleAudit("PREPAY_RULE_UPDATE", rule, operator);
        log.info("预收监控规则更新 code={} enabled={} threshold={}{} actor={}",
                rule.getCode(), rule.isEnabled(), rule.getThresholdValue(), rule.getThresholdUnit(), operator);
        return ruleView(rule, storeNameMap(rule));
    }

    /**
     * 应用可变更字段（ruleName/storeCode/thresholdValue/thresholdUnit/level/enabled/remark），
     * 逐字段校验。仅更新 body 显式给出的字段（PUT 语义按本卡 curl 验证场景全量提交，缺省回落现状）。
     */
    private void applyMutableFields(PrepayMonitorRule rule, Map<String, Object> body) {
        String type = rule.getType() == null ? requireType(body) : rule.getType();

        String name = body.get("ruleName") == null ? rule.getRuleName()
                : String.valueOf(body.get("ruleName")).trim();
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "规则名称 ruleName 不能为空");
        }
        if (name.length() > 64) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "规则名称 ruleName 最长 64 字");
        }
        rule.setRuleName(name);

        // 新建（type 尚未落库）时强制从 body 校验类型
        if (rule.getType() == null) {
            rule.setType(type);
        }

        String storeCode;
        if (!body.containsKey("storeCode")) {
            storeCode = rule.getStoreCode();
        } else {
            Object sc = body.get("storeCode");
            String trimmed = sc == null ? null : String.valueOf(sc).trim();
            storeCode = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
        }
        if (storeCode != null) {
            if (storeCode.length() > 16) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "门店编码 storeCode 最长 16 位");
            }
            if (!DataScope.canReadStore(storeCode)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "门店不存在或无权在该门店配置规则：" + storeCode);
            }
        }
        rule.setStoreCode(storeCode);

        Long threshold = parseThreshold(body.get("thresholdValue"), rule.getThresholdValue());
        if (threshold == null || threshold <= 0L) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "阈值 thresholdValue 必须为大于 0 的整数");
        }
        rule.setThresholdValue(threshold);

        String expectedUnit = TYPE_DEPOSIT_AGE.equals(type) ? "DAY" : "FEN";
        Object unitObj = body.get("thresholdUnit");
        String unit = unitObj == null || String.valueOf(unitObj).isBlank()
                ? expectedUnit : String.valueOf(unitObj).trim();
        if (!"DAY".equals(unit) && !"FEN".equals(unit)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "阈值单位 thresholdUnit 仅支持 DAY / FEN");
        }
        if (!unit.equals(expectedUnit)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    TYPE_DEPOSIT_AGE.equals(type)
                            ? "账龄规则 thresholdUnit 必须为 DAY（天）"
                            : "金额类规则 thresholdUnit 必须为 FEN（分）");
        }
        rule.setThresholdUnit(unit);

        Object levelObj = body.get("level");
        String level = levelObj == null || String.valueOf(levelObj).isBlank()
                ? rule.getLevel() : String.valueOf(levelObj).trim();
        if (level == null || !RULE_LEVELS.contains(level)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "告警级别 level 仅支持 HIGH / MEDIUM / LOW");
        }
        rule.setLevel(level);

        if (body.containsKey("enabled")) {
            rule.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        }

        if (body.containsKey("remark")) {
            Object rm = body.get("remark");
            String remark = rm == null ? null : truncate(String.valueOf(rm).trim(), 256);
            rule.setRemark(remark);
        }
    }

    private static String requireType(Map<String, Object> body) {
        Object t = body.get("type");
        String type = t == null ? null : String.valueOf(t).trim();
        if (type == null || !RULE_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "规则类型 type 非法：仅支持 DEPOSIT_AGE 充值账龄 / REFUND_PENDING 退款待核销 / DORMANT_CARD 沉睡卡沉淀");
        }
        return type;
    }

    private static Long parseThreshold(Object raw, Long fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "阈值 thresholdValue 必须为整数");
        }
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim();
    }

    /** 单规则视图的店名解析（全局规则返回空映射，避免一次无谓远程调用）。 */
    private Map<String, String> storeNameMap(PrepayMonitorRule r) {
        return r.getStoreCode() == null || r.getStoreCode().isBlank()
                ? Map.of()
                : aggregation.resolveStoreNames(List.of(r.getStoreCode()));
    }

    private Map<String, Object> ruleView(PrepayMonitorRule r, Map<String, String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.getCode());
        m.put("ruleName", r.getRuleName());
        m.put("type", r.getType());
        m.put("storeCode", r.getStoreCode());
        m.put("storeName", r.getStoreCode() == null ? null : names.get(r.getStoreCode()));
        m.put("thresholdValue", r.getThresholdValue());
        m.put("thresholdUnit", r.getThresholdUnit());
        m.put("level", r.getLevel());
        m.put("enabled", r.isEnabled());
        m.put("remark", r.getRemark());
        m.put("createdBy", r.getCreatedBy());
        m.put("updatedBy", r.getUpdatedBy());
        m.put("createdAt", r.getCreatedAt());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    private void ruleAudit(String action, PrepayMonitorRule rule, String actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", rule.getCode());
        payload.put("ruleName", rule.getRuleName());
        payload.put("type", rule.getType());
        payload.put("storeCode", rule.getStoreCode());
        payload.put("thresholdValue", rule.getThresholdValue());
        payload.put("thresholdUnit", rule.getThresholdUnit());
        payload.put("level", rule.getLevel());
        payload.put("enabled", rule.isEnabled());
        payload.put("remark", rule.getRemark());
        try {
            audit.record("COMPLIANCE", rule.getCode(), actor, action, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "审计内容序列化失败");
        }
    }

    // ==================== 三类规则评估 ====================

    /** 评估单规则命中门店 -> 命中金额（分）。返回 LinkedHashMap 保序，金额恒正。 */
    private Map<String, Long> evaluate(PrepayMonitorRule rule, LocalDate bizDate,
                                       List<FundEntryRepository.DepositAgeRow> depositRows,
                                       Map<String, Long> refundFenByStore,
                                       Map<String, Long> dormantFenByStore) {
        Map<String, Long> hits = new LinkedHashMap<>();
        long threshold = rule.getThresholdValue() == null ? 0L : rule.getThresholdValue();
        switch (rule.getType() == null ? "" : rule.getType()) {
            case TYPE_DEPOSIT_AGE -> {
                if (threshold <= 0L) break;
                OffsetDateTime ruleCutoff = bizDate.minusDays(threshold).atStartOfDay(BIZ_ZONE).toOffsetDateTime();
                for (FundEntryRepository.DepositAgeRow row : depositRows) {
                    if (row.getStoreCode() == null) continue;
                    long net = row.getNetFen() == null ? 0L : row.getNetFen();
                    if (net > 0L && row.getOldestInAt() != null
                            && row.getOldestInAt().isBefore(ruleCutoff)) {
                        hits.put(row.getStoreCode(), net);
                    }
                }
            }
            case TYPE_REFUND_PENDING -> refundFenByStore.forEach((sc, amt) -> {
                if (amt > threshold) hits.put(sc, amt);
            });
            case TYPE_DORMANT_CARD -> dormantFenByStore.forEach((sc, amt) -> {
                if (amt > threshold) hits.put(sc, amt);
            });
            default -> log.warn("预收监控规则类型未知，跳过 rule={} type={}", rule.getCode(), rule.getType());
        }
        return hits;
    }

    /** finance-flows.refunds（已固化 status=REFUNDED，全量历史）按店聚合退款金额（分）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Long> aggregateRefunds(Map<String, Object> flows) {
        Map<String, Long> byStore = new LinkedHashMap<>();
        Object raw = flows.get("refunds");
        if (!(raw instanceof List<?> list)) return byStore;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> r)) continue;
            String sc = str(r.get("storeCode"));
            if (sc == null || sc.isBlank()) continue;
            byStore.merge(sc, longOf(r.get("refundAmt")), Long::sum);
        }
        return byStore;
    }

    /**
     * 沉睡卡沉淀按店聚合：customer 投影 status 为中文卡状态，非「在用/退卡中」即休眠口径
     * （已退卡/已用完，对齐 FinanceAggregationService.mapCardStatus → DORMANT），
     * 且 balance&gt;0；仅计储值本金 balance，不含 giftBalance 赠金（对齐前端 dormantAmount）。
     */
    private Map<String, Long> aggregateDormantCards(List<Map<String, Object>> cards) {
        Map<String, Long> byStore = new LinkedHashMap<>();
        for (Map<String, Object> c : cards) {
            String sc = str(c.get("storeCode"));
            if (sc == null || sc.isBlank()) continue;
            String status = str(c.get("status"));
            if ("在用".equals(status) || "退卡中".equals(status)) continue;
            long balance = longOf(c.get("balance"));
            if (balance > 0L) {
                byStore.merge(sc, balance, Long::sum);
            }
        }
        return byStore;
    }

    // ==================== 事件生命周期 ====================

    /** 回转：该规则当前 OPEN 事件中，门店不在本轮命中集合内的，批量置 RESOLVED。 */
    private int resolveRule(PrepayMonitorRule rule, Set<String> hitStores) {
        List<PrepayMonitorEvent> opens =
                eventRepo.findByRuleCodeAndStatus(rule.getCode(), STATUS_OPEN);
        int n = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (PrepayMonitorEvent e : opens) {
            if (!hitStores.contains(e.getStoreCode())) {
                e.setStatus(STATUS_RESOLVED);
                e.setResolvedAt(now);
                eventRepo.save(e);
                n++;
            }
        }
        return n;
    }

    /** 落一条新 OPEN 事件（idem_key 带历史轮次，部分唯一索引兜底并发）。 */
    private PrepayMonitorEvent openEvent(PrepayMonitorRule rule, String storeCode, long amountFen,
                                         Map<String, String> names, OffsetDateTime now, LocalDate bizDate) {
        String storeName = names.getOrDefault(storeCode, storeCode);
        long round = eventRepo.countByRuleAndStore(rule.getCode(), storeCode) + 1L;

        PrepayMonitorEvent e = new PrepayMonitorEvent();
        e.setIdemKey("PM:" + rule.getCode() + ":" + storeCode + ":R" + round);
        e.setRuleCode(rule.getCode());
        e.setStoreCode(storeCode);
        e.setType(rule.getType());
        e.setLevel(rule.getLevel());
        e.setAmountFen(amountFen);
        e.setStatus(STATUS_OPEN);
        e.setFiredAt(now);
        e.setCreatedAt(now);
        e.setTitle(truncate(buildTitle(rule, storeName, amountFen), TITLE_MAX));
        e.setContent(truncate(buildContent(rule, storeName, amountFen), CONTENT_MAX));
        e.setBizRef(truncate("PM" + bizDate.format(BIZ_REF_DATE) + ":" + rule.getCode() + ":" + storeCode,
                BIZ_REF_MAX));
        return e;
    }

    private String buildTitle(PrepayMonitorRule rule, String storeName, long amountFen) {
        return switch (rule.getType()) {
            case TYPE_DEPOSIT_AGE -> "预收合规：" + storeName + " 充值沉淀超账龄阈值";
            case TYPE_REFUND_PENDING -> "预收合规：" + storeName + " 退款待核销金额超限";
            case TYPE_DORMANT_CARD -> "预收合规：" + storeName + " 沉睡卡沉淀金额超限";
            default -> "预收合规告警：" + storeName;
        } + "（" + rule.getCode() + "）";
    }

    private String buildContent(PrepayMonitorRule rule, String storeName, long amountFen) {
        String yuan = fenToYuan(amountFen);
        return switch (rule.getType()) {
            case TYPE_DEPOSIT_AGE -> "门店 " + storeName + " 预收充值净沉淀 ¥" + yuan
                    + "，最早一笔充值距今日已超 " + rule.getThresholdValue()
                    + " 天阈值，请核查长期未消耗预收款（规则 " + rule.getCode() + "）。";
            case TYPE_REFUND_PENDING -> "门店 " + storeName + " 已退款待银行/渠道回单核销金额合计 ¥" + yuan
                    + "，超过阈值 ¥" + fenToYuan(rule.getThresholdValue())
                    + "，请尽快完成对账核销（规则 " + rule.getCode() + "）。";
            case TYPE_DORMANT_CARD -> "门店 " + storeName + " 休眠卡（末次消费后无近期消耗）储值本金沉淀合计 ¥"
                    + yuan + "，超过阈值 ¥" + fenToYuan(rule.getThresholdValue())
                    + "，请跟进唤醒或合规处置（规则 " + rule.getCode() + "）。";
            default -> "门店 " + storeName + " 命中预收合规监控规则 " + rule.getCode()
                    + "，涉及金额 ¥" + yuan + "。";
        };
    }

    // ==================== 站内信外呼（软降级） ====================

    /**
     * 新发 OPEN 事件推送 txn compliance-alert。HIGH → CRITICAL（区域经理+超管），其余 → WARN（全量店长）；
     * 该端点不按门店收敛，门店信息已进 title/content/bizRef。返回是否成功（计入 alertsSent）。
     */
    private boolean sendAlert(PrepayMonitorRule rule, PrepayMonitorEvent event) {
        String level = "HIGH".equals(rule.getLevel()) ? "CRITICAL" : "WARN";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("level", level);
            body.put("title", event.getTitle());
            body.put("content", event.getContent());
            body.put("link", ALERT_LINK);
            body.put("bizRef", event.getBizRef());
            restTemplate.exchange(txnBaseUrl + "/api/txn/internal/compliance-alert",
                    HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            return true;
        } catch (Exception ex) {
            log.warn("预收监控站内信联动失败（txn 不可用，软降级下轮自愈）rule={} store={} : {}",
                    rule.getCode(), event.getStoreCode(), ex.getMessage());
            return false;
        }
    }

    // ==================== 审计 ====================

    private void audit(LocalDate bizDate, OffsetDateTime now, int ruleCount, int hitStores,
                       int eventsFired, int alertsSent, int resolved, List<Map<String, Object>> firedLog) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bizDate", bizDate.toString());
        payload.put("scannedAt", now.toString());
        payload.put("enabledRules", ruleCount);
        payload.put("hitStoreCount", hitStores);
        payload.put("eventsFired", eventsFired);
        payload.put("alertsSent", alertsSent);
        payload.put("eventsResolved", resolved);
        payload.put("fired", firedLog.size() > LIST_LIMIT ? firedLog.subList(0, LIST_LIMIT) : firedLog);
        try {
            audit.record("COMPLIANCE", "PM-SCAN-" + bizDate, ACTOR, "PREPAY_MONITOR",
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            // ObjectMapper 对 Map/基础类型不会抛；兜底手拼合法 JSON，绝不因审计阻断
            audit.record("COMPLIANCE", "PM-SCAN-" + bizDate, ACTOR, "PREPAY_MONITOR",
                    "{\"bizDate\":\"" + bizDate + "\",\"eventsFired\":" + eventsFired
                            + ",\"alertsSent\":" + alertsSent + ",\"eventsResolved\":" + resolved + "}");
        }
    }

    // ==================== 工具 ====================

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** JSON/Jackson 反序列化整数字段可能为 Integer/Long，统一按 long 收（金额单位分）。 */
    private static long longOf(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 分 → 元字符串（两位小数，千分位不用于告警正文，保持朴素口径）。 */
    private static String fenToYuan(long fen) {
        return String.format("%.2f", fen / 100.0);
    }

    private static String fenToYuan(Long fen) {
        return fenToYuan(fen == null ? 0L : fen);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
