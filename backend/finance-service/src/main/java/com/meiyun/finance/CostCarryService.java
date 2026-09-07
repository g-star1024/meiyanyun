package com.meiyun.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 月结成本结转服务（B11，DESIGN §四）：期末自动计提折旧与人工成本，后台可配、随时调整。
 *
 * <p>四类成本中 MATERIAL/LOSS 已随领用/报损终审实时闭环，结转模板仅覆盖：
 * <ul>
 *   <li>DEPRECIATION/ASSET：fin_asset 直线法月折旧按门店汇总（处置月起停折）；</li>
 *   <li>LABOR/BASE_SALARY：在岗咨询师/医生月底薪合计（staff_comp_config ACTIVE）；</li>
 *   <li>LABOR/COMMISSION：当月已审批（APPROVED/PAID）提成合计（commission_record）；</li>
 *   <li>FIXED：规则固定额（仅单店规则，分）。</li>
 * </ul>
 *
 * <p><b>资金红线</b>：结转只写成本镜像——fund_entry TK-DEPRECIATION/TK-LABOR OUT（bizType=COST、
 * source=SYSTEM、channel=null 无渠道、不动渠道账户），由落账钩子同写 cost_allocation 并重算月报；
 * 绝不生成任何实付渠道分录。幂等键 CARRY:{ruleId}:{periodMonth}:{storeCode}，同月同规则同门店不重复；
 * 「重算本月」先删 SYSTEM 结转行（fund_entry + cost_allocation + outbox 台账）再跑，<b>已封账月禁止重算/补结</b>。
 */
@Service
public class CostCarryService {

    private static final Logger log = LoggerFactory.getLogger(CostCarryService.class);

    static final Set<String> COST_TYPES = Set.of("DEPRECIATION", "LABOR");
    static final Set<String> CALC_MODES = Set.of("ASSET", "BASE_SALARY", "COMMISSION", "FIXED");

    private final CostCarryRuleRepository ruleRepo;
    private final FinAssetRepository assetRepo;
    private final StaffCompConfigRepository compRepo;
    private final CommissionRecordRepository commissionRepo;
    private final FundEntryRepository entryRepo;
    private final CostAllocationRepository costRepo;
    private final OutboxRepository outboxRepo;
    private final RevenueMonthlyRepository revRepo;
    private final FundEntryService fundEntryService;
    private final SettlementService settlementService;
    private final FinanceAggregationService aggregation;
    private final FinanceAuditRecorder audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CostCarryService(CostCarryRuleRepository ruleRepo,
                            FinAssetRepository assetRepo,
                            StaffCompConfigRepository compRepo,
                            CommissionRecordRepository commissionRepo,
                            FundEntryRepository entryRepo,
                            CostAllocationRepository costRepo,
                            OutboxRepository outboxRepo,
                            RevenueMonthlyRepository revRepo,
                            FundEntryService fundEntryService,
                            @Lazy SettlementService settlementService,
                            FinanceAggregationService aggregation,
                            FinanceAuditRecorder audit) {
        this.ruleRepo = ruleRepo;
        this.assetRepo = assetRepo;
        this.compRepo = compRepo;
        this.commissionRepo = commissionRepo;
        this.entryRepo = entryRepo;
        this.costRepo = costRepo;
        this.outboxRepo = outboxRepo;
        this.revRepo = revRepo;
        this.fundEntryService = fundEntryService;
        this.settlementService = settlementService;
        this.aggregation = aggregation;
        this.audit = audit;
    }

    // ==================== 测算（dry-run，不落库） ====================

    /**
     * 测算本月结转明细：POST /api/finance/carry/preview?month=yyyy-MM-01。
     * 逐启用规则 × 适用门店算金额，回带 executed（是否已结转）/basis（取数说明），不落库。
     * 数据域按登录人门店逐行收敛（无权门店不返回）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(LocalDate month, String actor) {
        LocalDate periodMonth = normalizeMonth(month);
        List<CarryLine> lines = buildLines(periodMonth);
        Set<String> doneKeys = existingCarryKeys(periodMonth);
        List<String> codes = lines.stream().map(l -> l.storeCode).distinct().toList();
        Map<String, String> names = codes.isEmpty() ? Map.of()
                : aggregation.resolveStoreNames(new ArrayList<>(codes));

        List<Map<String, Object>> details = new ArrayList<>();
        long totalAmount = 0, executedAmount = 0, pendingCount = 0, executedCount = 0;
        for (CarryLine l : lines) {
            if (!DataScope.canReadStore(l.storeCode)) continue;
            boolean executed = doneKeys.contains(l.idemKey);
            if (executed) executedCount++; else pendingCount++;
            totalAmount += l.amount;
            if (executed) executedAmount += l.amount;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("ruleId", l.ruleId);
            d.put("ruleName", l.ruleName);
            d.put("costType", l.costType);
            d.put("calcMode", l.calcMode);
            d.put("storeCode", l.storeCode);
            d.put("storeName", names.getOrDefault(l.storeCode, l.storeCode));
            d.put("amountFen", l.amount);
            d.put("basis", l.basis);
            d.put("executed", executed);
            details.add(d);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("month", periodMonth.toString());
        r.put("details", details);
        r.put("totalFen", totalAmount);
        r.put("executedFen", executedAmount);
        r.put("pendingFen", totalAmount - executedAmount);
        r.put("pendingCount", pendingCount);
        r.put("executedCount", executedCount);
        return r;
    }

    // ==================== 执行结转（幂等） ====================

    /**
     * 执行结转：POST /api/finance/carry/run?month=yyyy-MM-01。
     * recalc=true「重算本月」：先删该月 SYSTEM 结转行（fund_entry + cost_allocation）再跑
     * （已封账月 422 拒绝，封账不可改账）；已结转的规则×门店幂等跳过不双算。
     * 金额为 0 的明细跳过并回带 skipped；落 fund_entry 成本镜像（钩子同写 cost_allocation + 月报）。
     */
    @Transactional
    public Map<String, Object> run(LocalDate month, boolean recalc, String actor) {
        LocalDate periodMonth = normalizeMonth(month);
        List<String> affectedStores = List.of();
        if (recalc) {
            ensureMonthOpenForRecalc(periodMonth);
            affectedStores = deleteSystemCarry(periodMonth, "");
            List<String> reportStores = revRepo.findByPeriodMonthOrderByStoreCodeAsc(periodMonth)
                    .stream().map(RevenueMonthly::getStoreCode).toList();
            affectedStores = java.util.stream.Stream.concat(affectedStores.stream(), reportStores.stream())
                    .distinct().sorted().toList();
            audit("FIN_CARRY", "CARRY-RECALC:" + periodMonth, actor, "RECALC",
                    Map.of("month", periodMonth.toString()));
            log.info("成本结转重算：已删除 {} SYSTEM 结转行，month={} actor={} 待补刷门店={}",
                    periodMonth, actor, affectedStores);
        }
        List<CarryLine> lines = buildLines(periodMonth);
        Set<String> doneKeys = existingCarryKeys(periodMonth);
        OffsetDateTime occurred = periodMonth.withDayOfMonth(15).atTime(12, 0).atOffset(ZoneOffset.UTC);

        List<Map<String, Object>> posted = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        long postedAmount = 0;
        for (CarryLine l : lines) {
            if (!DataScope.canReadStore(l.storeCode)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleId", l.ruleId);
            item.put("ruleName", l.ruleName);
            item.put("storeCode", l.storeCode);
            item.put("costType", l.costType);
            item.put("amountFen", l.amount);
            if (l.amount <= 0) {
                item.put("reason", "金额为 0（无取数基数），跳过不结转");
                skipped.add(item);
                continue;
            }
            if (doneKeys.contains(l.idemKey)) {
                item.put("reason", "本月已结转，幂等跳过");
                item.put("duplicated", true);
                skipped.add(item);
                continue;
            }
            if (settlementService.isClosed(l.storeCode, occurred)) {
                // 闭期门店：postOne 本会 422 回滚整批，此处逐行跳过，未封门店继续补结
                item.put("reason", "该门店本月已封账，封账不可改账，跳过（差错走差异调平 ADJUST）");
                item.put("closed", true);
                skipped.add(item);
                continue;
            }
            String subject = "DEPRECIATION".equals(l.costType) ? "TK-DEPRECIATION" : "TK-LABOR";
            String bizRef = l.ruleId + ":" + periodMonth.toString().substring(0, 7) + ":" + l.storeCode;
            String label = "DEPRECIATION".equals(l.costType) ? "设备折旧计提" : "人工成本结转";
            String memo = label + " · " + l.ruleName + " · " + periodMonth + " · " + l.basis;
            FundEntryCmd cmd = new FundEntryCmd(l.idemKey, bizRef, "COST", subject, "OUT",
                    l.amount, null, "SYSTEM", "COST", l.storeCode, truncate(memo, 120), occurred.toString());
            List<Map<String, Object>> results = fundEntryService.postEntries(List.of(cmd), actor);
            item.putAll(results.get(0));
            posted.add(item);
            postedAmount += l.amount;
        }

        if (recalc) {
            for (String sc : affectedStores) {
                fundEntryService.recomputeRevenueMonthly(sc, periodMonth);
            }
            log.info("成本结转重算月报补刷完成 month={} 涉及门店 {} 家 actor={}",
                    periodMonth, affectedStores.size(), actor);
        }

        audit("FIN_CARRY", "CARRY-RUN:" + periodMonth, actor, recalc ? "RUN_RECALC" : "RUN",
                Map.of("month", periodMonth.toString(),
                        "postedCount", posted.size(),
                        "skippedCount", skipped.size(),
                        "postedFen", postedAmount,
                        "recalc", recalc));
        log.info("成本结转执行 month={} 落账 {} 笔 {} 分 跳过 {} 笔 recalc={} actor={}",
                periodMonth, posted.size(), postedAmount, skipped.size(), recalc, actor);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("month", periodMonth.toString());
        r.put("recalc", recalc);
        r.put("posted", posted);
        r.put("skipped", skipped);
        r.put("postedCount", posted.size());
        r.put("skippedCount", skipped.size());
        r.put("postedFen", postedAmount);
        r.put("message", posted.isEmpty()
                ? "本月结转无需新增（均已结转或金额为 0）"
                : "结转完成：" + posted.size() + " 项成本镜像已计提（不产生实付分录）");
        return r;
    }

    /**
     * 封账前置：月结（MONTH）封账前自动结转该店 run_on_close 且未结转的规则。
     * 由 SettlementService.close 同事务调用；幂等（已结转跳过）；已封账月不会走到（封账先幂等返回）。
     */
    @Transactional
    public Map<String, Object> autoCarryBeforeClose(LocalDate periodMonth, String storeCode, String actor) {
        List<CarryLine> lines = buildLines(periodMonth).stream()
                .filter(l -> storeCode.equals(l.storeCode) && l.ruleRunOnClose && l.amount > 0)
                .toList();
        Set<String> doneKeys = existingCarryKeys(periodMonth);
        OffsetDateTime occurred = periodMonth.withDayOfMonth(15).atTime(12, 0).atOffset(ZoneOffset.UTC);
        int carried = 0;
        long amount = 0;
        for (CarryLine l : lines) {
            if (doneKeys.contains(l.idemKey)) continue;
            String subject = "DEPRECIATION".equals(l.costType) ? "TK-DEPRECIATION" : "TK-LABOR";
            String bizRef = l.ruleId + ":" + periodMonth.toString().substring(0, 7) + ":" + l.storeCode;
            String label = "DEPRECIATION".equals(l.costType) ? "设备折旧计提" : "人工成本结转";
            String memo = "封账自动结转 · " + label + " · " + l.ruleName + " · " + periodMonth;
            FundEntryCmd cmd = new FundEntryCmd(l.idemKey, bizRef, "COST", subject, "OUT",
                    l.amount, null, "SYSTEM", "COST", l.storeCode, truncate(memo, 120), occurred.toString());
            fundEntryService.postEntries(List.of(cmd), actor);
            carried++;
            amount += l.amount;
        }
        if (carried > 0) {
            audit("FIN_CARRY", "CARRY-CLOSE:" + periodMonth + ":" + storeCode, actor, "AUTO_ON_CLOSE",
                    Map.of("month", periodMonth.toString(),
                            "storeCode", storeCode,
                            "carriedCount", carried,
                            "carriedFen", amount));
            log.info("封账自动结转 month={} store={} 补结 {} 笔 {} 分 actor={}",
                    periodMonth, storeCode, carried, amount, actor);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("month", periodMonth.toString());
        r.put("storeCode", storeCode);
        r.put("carriedCount", carried);
        r.put("carriedFen", amount);
        return r;
    }

    /**
     * 未结转提示：GET /api/finance/carry/pending?month=&storeCode=。
     * 返回该范围 run_on_close 启用规则中本月尚未结转的项数/金额（封账页「本月 N 项结转未执行」）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> pending(LocalDate month, String storeCode) {
        LocalDate periodMonth = normalizeMonth(month);
        Set<String> doneKeys = existingCarryKeys(periodMonth);
        long pendingCount = 0, pendingFen = 0;
        for (CarryLine l : buildLines(periodMonth)) {
            if (storeCode != null && !storeCode.isBlank() && !storeCode.equals(l.storeCode)) continue;
            if (!l.ruleRunOnClose || l.amount <= 0) continue;
            if (!DataScope.canReadStore(l.storeCode)) continue;
            if (doneKeys.contains(l.idemKey)) continue;
            pendingCount++;
            pendingFen += l.amount;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("month", periodMonth.toString());
        r.put("storeCode", storeCode);
        r.put("pendingCount", pendingCount);
        r.put("pendingFen", pendingFen);
        return r;
    }

    // ==================== 规则维护（后台可配） ====================

    @Transactional(readOnly = true)
    public List<CostCarryRule> listRules() {
        return ruleRepo.findAllByOrderByRuleIdAsc();
    }

    /** 新建结转规则：CCR+yyyyMMdd-6 位序号。 */
    @Transactional
    public CostCarryRule createRule(String ruleName, String costType, String calcMode,
                                    Long fixedAmount, String storeCode, Boolean enabled,
                                    Boolean runOnClose, String remark, String actor) {
        if (isBlank(ruleName)) throw err("规则名称 ruleName 不能为空");
        String ct = normalizeCostType(costType);
        String cm = normalizeCalcMode(calcMode);
        validateMode(ct, cm, fixedAmount, storeCode);
        CostCarryRule r = new CostCarryRule();
        r.setRuleId(nextRuleNo());
        r.setRuleName(ruleName.trim());
        r.setCostType(ct);
        r.setCalcMode(cm);
        if ("FIXED".equals(cm)) r.setFixedAmount(fixedAmount);
        r.setStoreCode(normalizeStore(storeCode));
        r.setEnabled(enabled == null || enabled);
        r.setRunOnClose(runOnClose == null || runOnClose);
        r.setRemark(remark);
        r.setCreatedBy(actor);
        r.setUpdatedBy(actor);
        OffsetDateTime now = OffsetDateTime.now();
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        ruleRepo.save(r);
        audit("FIN_CARRY", r.getRuleId(), actor, "RULE_CREATE",
                Map.of("ruleName", r.getRuleName(), "costType", ct, "calcMode", cm,
                        "storeCode", r.getStoreCode() == null ? "" : r.getStoreCode()));
        log.info("结转规则新建 {} {} {}/{} store={} actor={}", r.getRuleId(), r.getRuleName(), ct, cm, r.getStoreCode(), actor);
        return r;
    }

    /** 更新规则（改名/固定额/适用门店/启停/封账自动/备注），字段缺省不改；停用不追溯已结转期间。 */
    @Transactional
    public CostCarryRule updateRule(String ruleId, Map<String, Object> body, String actor) {
        CostCarryRule r = ruleRepo.findById(ruleId)
                .orElseThrow(() -> err404("结转规则不存在：" + ruleId));
        if (body.get("ruleName") != null && !String.valueOf(body.get("ruleName")).isBlank()) {
            r.setRuleName(String.valueOf(body.get("ruleName")).trim());
        }
        String ct = body.get("costType") == null ? r.getCostType() : normalizeCostType(String.valueOf(body.get("costType")));
        String cm = body.get("calcMode") == null ? r.getCalcMode() : normalizeCalcMode(String.valueOf(body.get("calcMode")));
        Long fixedAmount = body.get("fixedAmount") == null ? r.getFixedAmount()
                : Long.valueOf(String.valueOf(body.get("fixedAmount")));
        String storeCode = body.containsKey("storeCode")
                ? normalizeStore(String.valueOf(body.get("storeCode"))) : r.getStoreCode();
        validateMode(ct, cm, fixedAmount, storeCode);
        r.setCostType(ct);
        r.setCalcMode(cm);
        r.setFixedAmount("FIXED".equals(cm) ? fixedAmount : null);
        r.setStoreCode(storeCode);
        if (body.get("enabled") != null) r.setEnabled(Boolean.valueOf(String.valueOf(body.get("enabled"))));
        if (body.get("runOnClose") != null) r.setRunOnClose(Boolean.valueOf(String.valueOf(body.get("runOnClose"))));
        if (body.containsKey("remark")) {
            Object rm = body.get("remark");
            r.setRemark(rm == null ? null : String.valueOf(rm));
        }
        r.setUpdatedBy(actor);
        r.setUpdatedAt(OffsetDateTime.now());
        ruleRepo.save(r);
        audit("FIN_CARRY", r.getRuleId(), actor, "RULE_UPDATE",
                Map.of("enabled", r.getEnabled(), "runOnClose", r.getRunOnClose(),
                        "calcMode", r.getCalcMode(), "storeCode", r.getStoreCode() == null ? "" : r.getStoreCode()));
        log.info("结转规则更新 {} enabled={} runOnClose={} actor={}", ruleId, r.getEnabled(), r.getRunOnClose(), actor);
        return r;
    }

    // ==================== 设备资产台账 ====================

    @Transactional(readOnly = true)
    public List<FinAsset> listAssets(String storeCode) {
        if (storeCode != null && !storeCode.isBlank()) {
            List<FinAsset> all = new ArrayList<>();
            all.addAll(assetRepo.findByStoreCodeAndStatusOrderByAssetIdAsc(storeCode.trim(), "IN_USE"));
            all.addAll(assetRepo.findByStoreCodeAndStatusOrderByAssetIdAsc(storeCode.trim(), "DISPOSED"));
            return all;
        }
        List<FinAsset> all = new ArrayList<>();
        all.addAll(assetRepo.findByStatusOrderByAssetIdAsc("IN_USE"));
        all.addAll(assetRepo.findByStatusOrderByAssetIdAsc("DISPOSED"));
        return all;
    }

    /** 新增设备资产：FA+yyyyMMdd-6 位序号；起折月归一 yyyy-MM-01。 */
    @Transactional
    public FinAsset createAsset(String assetName, String storeCode, Long originalValue,
                                Integer salvageRate, Integer usefulMonths, LocalDate startMonth,
                                String actor) {
        if (isBlank(assetName)) throw err("设备名称 assetName 不能为空");
        if (isBlank(storeCode)) throw err("所属门店 storeCode 不能为空");
        if (originalValue == null || originalValue <= 0) throw err("原值 originalValue 必须为正数（单位：分）");
        if (usefulMonths == null || usefulMonths <= 0) throw err("折旧月限 usefulMonths 必须为正整数（如 120 = 10 年）");
        int rate = salvageRate == null ? 5 : salvageRate;
        if (rate < 0 || rate >= 100) throw err("残值率 salvageRate 需为 0-99 的百分比整数（默认 5）");
        LocalDate start = startMonth == null ? LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
                : startMonth.withDayOfMonth(1);
        FinAsset a = new FinAsset();
        a.setAssetId(nextAssetNo());
        a.setAssetName(assetName.trim());
        a.setStoreCode(storeCode.trim());
        a.setOriginalValue(originalValue);
        a.setSalvageRate(rate);
        a.setUsefulMonths(usefulMonths);
        a.setStartMonth(start);
        a.setStatus("IN_USE");
        a.setCreatedBy(actor);
        a.setUpdatedBy(actor);
        OffsetDateTime now = OffsetDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        assetRepo.save(a);
        audit("FIN_CARRY", a.getAssetId(), actor, "ASSET_CREATE",
                Map.of("assetName", a.getAssetName(), "storeCode", a.getStoreCode(),
                        "originalValueFen", originalValue, "usefulMonths", usefulMonths,
                        "monthlyDepreciationFen", a.monthlyDepreciation()));
        log.info("设备资产新增 {} {} store={} 月折 {} 分 actor={}",
                a.getAssetId(), a.getAssetName(), a.getStoreCode(), a.monthlyDepreciation(), actor);
        return a;
    }

    /** 资产处置：IN_USE → DISPOSED（处置月起停折，不删档案）。 */
    @Transactional
    public FinAsset disposeAsset(String assetId, String actor) {
        FinAsset a = assetRepo.findById(assetId)
                .orElseThrow(() -> err404("设备资产不存在：" + assetId));
        if ("DISPOSED".equals(a.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该资产已处置，无需重复处置");
        }
        a.setStatus("DISPOSED");
        a.setUpdatedBy(actor);
        a.setUpdatedAt(OffsetDateTime.now());
        assetRepo.save(a);
        audit("FIN_CARRY", a.getAssetId(), actor, "ASSET_DISPOSE",
                Map.of("assetName", a.getAssetName(), "storeCode", a.getStoreCode()));
        log.info("设备资产处置 {} {} store={} actor={}", assetId, a.getAssetName(), a.getStoreCode(), actor);
        return a;
    }

    // ==================== 内部：取数与编排 ====================

    /** 逐启用规则 × 适用门店算金额（不落库）。FIXED 仅单店；其余规则按有基数的门店展开。 */
    private List<CarryLine> buildLines(LocalDate periodMonth) {
        List<CarryLine> lines = new ArrayList<>();
        List<CostCarryRule> rules = ruleRepo.findByEnabledTrueOrderByRuleIdAsc();
        for (CostCarryRule rule : rules) {
            String ruleStore = normalizeStore(rule.getStoreCode());
            switch (rule.getCalcMode()) {
                case "ASSET" -> {
                    Map<String, Long> byStore = new LinkedHashMap<>();
                    Map<String, List<String>> basis = new LinkedHashMap<>();
                    for (FinAsset a : assetRepo.findByStatusOrderByAssetIdAsc("IN_USE")) {
                        // 起折月之后（含当月）才计提
                        if (a.getStartMonth() != null && a.getStartMonth().isAfter(periodMonth)) continue;
                        long dep = a.monthlyDepreciation();
                        if (dep <= 0) continue;
                        byStore.merge(a.getStoreCode(), dep, Long::sum);
                        basis.computeIfAbsent(a.getStoreCode(), k -> new ArrayList<>())
                                .add(a.getAssetName() + " 月折" + (dep / 100) + "元");
                    }
                    for (Map.Entry<String, Long> e : byStore.entrySet()) {
                        if (ruleStore != null && !ruleStore.equals(e.getKey())) continue;
                        lines.add(line(rule, e.getKey(), e.getValue(),
                                "资产直线法汇总：" + String.join("、", basis.get(e.getKey())), periodMonth));
                    }
                }
                case "BASE_SALARY" -> {
                    Map<String, Long> byStore = new LinkedHashMap<>();
                    Map<String, Integer> heads = new LinkedHashMap<>();
                    for (StaffCompConfig c : compRepo.findByStatusOrderByStaffIdAsc("ACTIVE")) {
                        byStore.merge(c.getStoreCode(), c.getBaseSalary(), Long::sum);
                        heads.merge(c.getStoreCode(), 1, Integer::sum);
                    }
                    for (Map.Entry<String, Long> e : byStore.entrySet()) {
                        if (ruleStore != null && !ruleStore.equals(e.getKey())) continue;
                        lines.add(line(rule, e.getKey(), e.getValue(),
                                "在岗底薪合计 " + heads.get(e.getKey()) + " 人", periodMonth));
                    }
                }
                case "COMMISSION" -> {
                    Map<String, Long> byStore = new LinkedHashMap<>();
                    for (CommissionRecord r : commissionRepo.findByPeriodOrderByCommissionDesc(periodMonth)) {
                        if (!"APPROVED".equals(r.getStatus()) && !"PAID".equals(r.getStatus())) continue;
                        byStore.merge(r.getStoreCode(), r.getCommission(), Long::sum);
                    }
                    for (Map.Entry<String, Long> e : byStore.entrySet()) {
                        if (ruleStore != null && !ruleStore.equals(e.getKey())) continue;
                        lines.add(line(rule, e.getKey(), e.getValue(), "当月已审批/已发放提成合计", periodMonth));
                    }
                }
                case "FIXED" -> {
                    if (ruleStore == null) continue; // FIXED 必须指定门店（保存时已拦，防御）
                    lines.add(line(rule, ruleStore, rule.getFixedAmount() == null ? 0L : rule.getFixedAmount(),
                            "固定额每月计提", periodMonth));
                }
                default -> { }
            }
        }
        return lines;
    }

    private CarryLine line(CostCarryRule rule, String storeCode, long amount, String basis, LocalDate periodMonth) {
        CarryLine l = new CarryLine();
        l.ruleId = rule.getRuleId();
        l.ruleName = rule.getRuleName();
        l.costType = rule.getCostType();
        l.calcMode = rule.getCalcMode();
        l.ruleRunOnClose = Boolean.TRUE.equals(rule.getRunOnClose());
        l.storeCode = storeCode;
        l.amount = amount;
        l.basis = basis;
        l.idemKey = "CARRY:" + rule.getRuleId() + ":" + periodMonth.toString().substring(0, 7) + ":" + storeCode;
        return l;
    }

    /**
     * 重算删除：fund_entry SYSTEM 结转分录 + cost_allocation 对应成本行 + outbox 成本台账行（同事务）。
     * outbox 按 txn_no 中段目标月匹配（txn_no=ruleId:yyyy-MM:storeCode），不按 created_at——
     * 跨月重算时执行时刻不在目标月窗口内，created_at 会漏删致台账翻倍。
     * 返回删除前曾有结转分录的门店集合，供删除+重落后补刷月报（成本归零门店无新分录触发刷新）。
     */
    private List<String> deleteSystemCarry(LocalDate periodMonth, String storeCode) {
        OffsetDateTime from = periodMonth.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime to = periodMonth.plusMonths(1).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        String monthToken = periodMonth.toString().substring(0, 7);
        String store = storeCode == null ? "" : storeCode;
        List<String> affectedStores = "".equals(store)
                ? entryRepo.findCarryStoreCodes(from, to)
                : List.of(store);
        int entries = entryRepo.deleteSystemCarry(from, to, store);
        int costs = costRepo.deleteSystemCarry(periodMonth, store);
        int outbox = outboxRepo.deleteSystemCarry(monthToken, store);
        log.info("删除 SYSTEM 结转行：fund_entry {} 行、cost_allocation {} 行、outbox_record {} 行（month={} store={} 待补刷门店 {}）",
                entries, costs, outbox, periodMonth, storeCode, affectedStores.size());
        return affectedStores;
    }

    /**
     * 重算前置封账拦截：重算删除是全门店范围，该月任一门店已封账即拒绝
     * （删除后封账门店的分录无法重落、封账快照会失真；封账不可逆）。
     * 非重算执行不整体拦截——闭期门店在逐行落账时跳过（见 run()），未封门店正常补结。
     */
    private void ensureMonthOpenForRecalc(LocalDate periodMonth) {
        if (settlementService.isMonthClosedAnyStore(periodMonth)) {
            throw err("该月已有门店封账（封账不可改账），不能重算本月结转；如需补结请逐月对未封门店执行");
        }
    }

    private Set<String> existingCarryKeys(LocalDate periodMonth) {
        OffsetDateTime from = periodMonth.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime to = periodMonth.plusMonths(1).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        return Set.copyOf(entryRepo.findCarryIdemKeys(from, to));
    }

    private void validateMode(String costType, String calcMode, Long fixedAmount, String storeCode) {
        if ("DEPRECIATION".equals(costType) && !"ASSET".equals(calcMode) && !"FIXED".equals(calcMode)) {
            throw err("折旧（DEPRECIATION）结转仅支持 ASSET 资产折旧 / FIXED 固定额取数方式");
        }
        if ("LABOR".equals(costType) && "ASSET".equals(calcMode)) {
            throw err("人工（LABOR）结转不支持 ASSET 取数，允许 BASE_SALARY/COMMISSION/FIXED");
        }
        if ("FIXED".equals(calcMode)) {
            if (fixedAmount == null || fixedAmount <= 0) throw err("FIXED 固定额规则必须填正数 fixedAmount（单位：分）");
            if (isBlank(storeCode)) throw err("FIXED 固定额规则必须指定适用门店 storeCode（全门店通用请改用自动取数方式）");
        }
    }

    private String normalizeCostType(String s) {
        if (isBlank(s)) throw err("成本类型 costType 不能为空（DEPRECIATION 折旧 / LABOR 人工）");
        String v = s.trim().toUpperCase();
        if (!COST_TYPES.contains(v)) throw err("成本类型 costType 非法，允许值：DEPRECIATION 折旧 / LABOR 人工");
        return v;
    }

    private String normalizeCalcMode(String s) {
        if (isBlank(s)) throw err("取数方式 calcMode 不能为空（ASSET/BASE_SALARY/COMMISSION/FIXED）");
        String v = s.trim().toUpperCase();
        if (!CALC_MODES.contains(v))
            throw err("取数方式 calcMode 非法，允许值：ASSET 资产折旧 / BASE_SALARY 底薪合计 / COMMISSION 已审批提成 / FIXED 固定额");
        return v;
    }

    private String normalizeStore(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private LocalDate normalizeMonth(LocalDate month) {
        if (month == null) throw err("归属月份 month 不能为空（yyyy-MM-01）");
        return month.withDayOfMonth(1);
    }

    private synchronized String nextRuleNo() {
        String day = LocalDate.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "CCR" + day + "-";
        long seq = 1 + ruleRepo.maxSeqOfDay(prefix + "%");
        return prefix + String.format("%06d", seq);
    }

    private synchronized String nextAssetNo() {
        String day = LocalDate.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "FA" + day + "-";
        long seq = 1 + assetRepo.maxSeqOfDay(prefix + "%");
        return prefix + String.format("%06d", seq);
    }

    private void audit(String bizType, String txnNo, String actor, String action, Map<String, Object> payload) {
        try {
            audit.record(bizType, txnNo, actor == null ? "system" : actor, action, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("结转审计 payload 序列化失败: {}", e.getMessage());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static ResponseStatusException err(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    private static ResponseStatusException err404(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    /** 结转测算明细行（内部结构）。 */
    private static class CarryLine {
        String ruleId;
        String ruleName;
        String costType;
        String calcMode;
        boolean ruleRunOnClose;
        String storeCode;
        long amount;
        String basis;
        String idemKey;
    }
}
