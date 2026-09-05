package com.meiyun.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B5 三页（预算/发票/财务设置）后端持久化。
 *
 * <p>三页均为财务配置/凭证域，<b>不触达资金池</b>：预算仅额度管控对比，发票仅凭证登记，
 * 设置仅配置参数；写接口四件套齐备（中文 422 校验、幂等、全审计、状态机）。
 * 金额口径：DB 存 Long「分」，视图返回「元」（/100.0 round2）；税率 BigDecimal(5,4) 0~1。
 */
@Service
public class FinConfigService {

    private static final Logger log = LoggerFactory.getLogger(FinConfigService.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final Long SETTING_ID = 1L;

    /** 8 个预算科目（与前端 finBudget BudgetSubjectId 对齐）。 */
    private static final List<String> BUDGET_SUBJECTS = List.of(
            "REVENUE", "COST", "MATERIAL", "LABOR", "DEPRECIATION", "LOSS", "MARKETING", "RENT");

    /** 11 个会计科目码（与前端 financeCore SUBJECT_LABEL 对齐）；RF-RECEIVABLE 默认停用。 */
    private static final List<String> SUBJECT_CODES = List.of(
            "RF-CASH", "RF-BANK", "RF-RECEIVABLE", "RF-DEPOSIT", "RF-REVENUE", "RF-REFUND",
            "TK-MATERIAL", "TK-COST", "TK-DEPRECIATION", "TK-LOSS", "TK-LABOR");
    private static final Map<String, String> SUBJECT_LABEL = Map.ofEntries(
            Map.entry("RF-CASH", "库存现金"),
            Map.entry("RF-BANK", "银行存款"),
            Map.entry("RF-RECEIVABLE", "应收账款"),
            Map.entry("RF-DEPOSIT", "预收账款"),
            Map.entry("RF-REVENUE", "主营业务收入"),
            Map.entry("RF-REFUND", "退款（收入抵减）"),
            Map.entry("TK-MATERIAL", "耗材库存"),
            Map.entry("TK-COST", "主营业务成本"),
            Map.entry("TK-DEPRECIATION", "设备折旧"),
            Map.entry("TK-LOSS", "报损"),
            Map.entry("TK-LABOR", "人工分摊"));

    private static final Set<String> INVOICE_TYPES = Set.of("NORMAL", "SPECIAL", "ELECTRONIC");
    private static final Set<String> INVOICE_CATEGORIES = Set.of("SERVICE", "PRODUCT", "MEMBERSHIP");
    private static final Set<BigDecimal> INVOICE_RATES =
            Set.of(bd("0"), bd("0.01"), bd("0.03"), bd("0.06"), bd("0.13"));

    private final FinBudgetRepository budgetRepo;
    private final FinInvoiceRepository invoiceRepo;
    private final FinSettingRepository settingRepo;
    private final FinSubjectEnableRepository subjectRepo;
    private final FinChangeLogRepository changeLogRepo;
    private final FinanceAggregationService aggregation;
    private final FinanceAuditRecorder audit;
    private final ObjectMapper objectMapper;

    public FinConfigService(FinBudgetRepository budgetRepo, FinInvoiceRepository invoiceRepo,
                            FinSettingRepository settingRepo, FinSubjectEnableRepository subjectRepo,
                            FinChangeLogRepository changeLogRepo, FinanceAggregationService aggregation,
                            FinanceAuditRecorder audit, ObjectMapper objectMapper) {
        this.budgetRepo = budgetRepo;
        this.invoiceRepo = invoiceRepo;
        this.settingRepo = settingRepo;
        this.subjectRepo = subjectRepo;
        this.changeLogRepo = changeLogRepo;
        this.aggregation = aggregation;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    // ==================== 预算 ====================

    /** 年度预算（元）。year 缺省取当前年；未配置科目回落前端默认预算（仅首次空库时）。 */
    public Map<String, Object> listBudgets(Integer year) {
        int y = year != null ? year : LocalDate.now(CN).getYear();
        List<FinBudget> rows = budgetRepo.findByBudgetYearOrderByBudgetIdAsc(y);
        Map<String, Long> bySubject = new LinkedHashMap<>();
        for (FinBudget b : rows) bySubject.put(b.getSubjectCode(), b.getBudgetFen());
        List<Map<String, Object>> subjects = new ArrayList<>();
        for (String code : BUDGET_SUBJECTS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subjectCode", code);
            Long fen = bySubject.get(code);
            m.put("budgetYuan", fen == null ? defaultBudgetYuan(code) : yuan(fen));
            m.put("updatedBy", updatedByOf(rows, code));
            subjects.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", y);
        out.put("subjects", subjects);
        return out;
    }

    /** 批量保存年度预算（年+科目幂等 upsert）。budgets 为 科目码→元。 */
    @Transactional
    public Map<String, Object> saveBudgets(Integer year, Map<String, Object> budgets, String actor) {
        int y = year != null ? year : LocalDate.now(CN).getYear();
        if (budgets == null || budgets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "预算内容不能为空");
        }
        OffsetDateTime now = OffsetDateTime.now();
        int changed = 0;
        List<Map<String, Object>> detail = new ArrayList<>();
        for (String code : BUDGET_SUBJECTS) {
            if (!budgets.containsKey(code)) continue;
            long fen = parsePositiveFen(budgets.get(code), "预算金额");
            FinBudget b = budgetRepo.findByBudgetYearAndSubjectCode(y, code).orElseGet(FinBudget::new);
            Long old = b.getBudgetFen();
            b.setBudgetYear(y);
            b.setSubjectCode(code);
            b.setBudgetFen(fen);
            b.setUpdatedAt(now);
            b.setUpdatedBy(actor);
            budgetRepo.save(b);
            if (old == null || old != fen) {
                changed++;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("subject", code);
                d.put("oldYuan", old == null ? 0 : yuan(old));
                d.put("newYuan", yuan(fen));
                detail.add(d);
            }
        }
        if (changed > 0) {
            audit("FIN_BUDGET", "BUDGET-" + y, actor, "SAVE",
                    Map.of("year", y, "changed", changed, "detail", detail));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", y);
        out.put("changed", changed);
        return out;
    }

    // ==================== 发票 ====================

    /** 发票列表（元，门店中文名已解析）；数据域按登录人门店强制收敛，可按 status/type/keyword 过滤。 */
    public List<Map<String, Object>> listInvoices(String storeCode, String status, String type, String keyword) {
        List<FinInvoice> all = invoiceRepo.findAll((root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(DataScope.<FinInvoice>storeSpec("storeCode").toPredicate(root, q, cb));
            if (storeCode != null && !storeCode.isBlank()) {
                ps.add(cb.equal(root.get("storeCode"), storeCode));
            }
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (type != null && !type.isBlank()) {
                ps.add(cb.equal(root.get("type"), type));
            }
            if (keyword != null && !keyword.isBlank()) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("invoiceNo")), kw),
                        cb.like(cb.lower(root.get("title")), kw),
                        cb.like(cb.lower(root.get("buyerName")), kw),
                        cb.like(cb.lower(cb.coalesce(root.get("taxNo"), "")), kw)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        }, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.desc("issuedAt"),
                org.springframework.data.domain.Sort.Order.desc("invoiceId")));

        Set<String> codes = new LinkedHashSet<>();
        all.forEach(i -> codes.add(i.getStoreCode()));
        Map<String, String> names = aggregation.resolveStoreNames(new ArrayList<>(codes));
        List<Map<String, Object>> out = new ArrayList<>();
        for (FinInvoice i : all) out.add(invoiceView(i, names));
        return out;
    }

    /** 新建发票（草稿 DRAFT）。票号服务端生成，税额服务端计算，idem_key 幂等。 */
    @Transactional
    public Map<String, Object> createInvoice(Map<String, Object> body, String actor) {
        String idemKey = str(body.get("idemKey"));
        if (idemKey != null) {
            var dup = invoiceRepo.findByIdemKey(idemKey);
            if (dup.isPresent()) {
                Map<String, Object> v = invoiceView(dup.get(),
                        aggregation.resolveStoreNames(List.of(dup.get().getStoreCode())));
                v.put("duplicated", true);
                return v;
            }
        }
        String type = str(body.get("type"));
        String category = str(body.get("category"));
        String title = str(body.get("title"));
        String buyerName = str(body.get("buyerName"));
        String storeCode = str(body.get("storeCode"));
        if (type == null || !INVOICE_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "票种不合法（NORMAL/SPECIAL/ELECTRONIC）");
        }
        if (category == null || !INVOICE_CATEGORIES.contains(category)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "项目类别不合法（SERVICE/PRODUCT/MEMBERSHIP）");
        }
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "发票抬头不能为空");
        }
        if (buyerName == null || buyerName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "购方客户不能为空");
        }
        if (storeCode == null || storeCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "开票门店不能为空");
        }
        if (!DataScope.canReadStore(storeCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权在该门店登记发票");
        }
        long amount = parsePositiveFen(body.get("amount"), "价税合计");
        BigDecimal rate = parseInvoiceRate(body.get("taxRate"));
        long taxAmount = computeTaxFen(amount, rate);
        String taxNo = str(body.get("taxNo"));
        String remark = truncate(str(body.get("remark")), 256);
        String orderRefs = normalizeRefs(body.get("orderRefs"));

        FinInvoice inv = new FinInvoice();
        inv.setInvoiceNo(nextInvoiceNo());
        inv.setType(type);
        inv.setCategory(category);
        inv.setTitle(truncate(title.trim(), 128));
        inv.setTaxNo(taxNo == null ? null : truncate(taxNo.trim(), 32));
        inv.setAmount(amount);
        inv.setTaxAmount(taxAmount);
        inv.setTaxRate(rate);
        inv.setBuyerName(truncate(buyerName.trim(), 64));
        inv.setOrderRefs(orderRefs);
        inv.setStoreCode(storeCode);
        inv.setStatus("DRAFT");
        inv.setOperator(actor);
        inv.setReviewer(null);
        inv.setRemark(remark);
        inv.setIdemKey(idemKey);
        OffsetDateTime now = OffsetDateTime.now();
        inv.setCreatedAt(now);
        inv.setIssuedAt(now);
        invoiceRepo.save(inv);

        audit("FIN_INVOICE", inv.getInvoiceNo(), actor, "CREATE",
                Map.of("invoiceNo", inv.getInvoiceNo(), "type", type, "category", category,
                        "amountYuan", yuan(amount), "taxYuan", yuan(taxAmount), "storeCode", storeCode,
                        "title", inv.getTitle()));
        return invoiceView(inv, aggregation.resolveStoreNames(List.of(storeCode)));
    }

    /** 开具：DRAFT → ISSUED（finance:invoice:edit）。 */
    @Transactional
    public Map<String, Object> issue(Long id, String reviewer, String actor) {
        FinInvoice inv = mustInvoice(id);
        if (!"DRAFT".equals(inv.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅待开票（DRAFT）发票可开具，当前状态：" + inv.getStatus());
        }
        String rv = (reviewer == null || reviewer.isBlank()) ? actor : reviewer.trim();
        inv.setStatus("ISSUED");
        inv.setReviewer(truncate(rv, 64));
        inv.setIssuedAt(OffsetDateTime.now());
        invoiceRepo.save(inv);
        audit("FIN_INVOICE", inv.getInvoiceNo(), actor, "ISSUE",
                Map.of("invoiceNo", inv.getInvoiceNo(), "reviewer", rv, "amountYuan", yuan(inv.getAmount())));
        return invoiceView(inv, aggregation.resolveStoreNames(List.of(inv.getStoreCode())));
    }

    /** 作废：ISSUED → VOIDED（finance:invoice:edit），需填写作废原因。 */
    @Transactional
    public Map<String, Object> voidInvoice(Long id, String reason, String actor) {
        FinInvoice inv = mustInvoice(id);
        if (!"ISSUED".equals(inv.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅已开票（ISSUED）发票可作废，当前状态：" + inv.getStatus());
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "作废原因不能为空");
        }
        inv.setStatus("VOIDED");
        inv.setRemark(truncate(reason.trim(), 256));
        invoiceRepo.save(inv);
        audit("FIN_INVOICE", inv.getInvoiceNo(), actor, "VOID",
                Map.of("invoiceNo", inv.getInvoiceNo(), "reason", reason.trim()));
        return invoiceView(inv, aggregation.resolveStoreNames(List.of(inv.getStoreCode())));
    }

    /** 红冲：ISSUED → RED_FLUSHED（finance:invoice:approve，双签），需填写红冲原因。 */
    @Transactional
    public Map<String, Object> redFlush(Long id, String reason, String actor) {
        FinInvoice inv = mustInvoice(id);
        if (!"ISSUED".equals(inv.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅已开票（ISSUED）发票可红冲，当前状态：" + inv.getStatus());
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "红冲原因不能为空");
        }
        inv.setStatus("RED_FLUSHED");
        inv.setRemark(truncate(reason.trim(), 256));
        invoiceRepo.save(inv);
        audit("FIN_INVOICE", inv.getInvoiceNo(), actor, "RED_FLUSH",
                Map.of("invoiceNo", inv.getInvoiceNo(), "reason", reason.trim()));
        return invoiceView(inv, aggregation.resolveStoreNames(List.of(inv.getStoreCode())));
    }

    // ==================== 财务设置 ====================

    /** 设置（元/百分号口径同前端）+ 科目启用表 + 最近变更日志。行不存在回落内置默认。 */
    public Map<String, Object> getSettings() {
        FinSetting s = settingRepo.findById(SETTING_ID).orElse(null);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("vatRate", s != null && s.getVatRate() != null ? s.getVatRate() : bd("0.06"));
        settings.put("surtaxRate", s != null && s.getSurtaxRate() != null ? s.getSurtaxRate() : bd("0.12"));
        settings.put("incomeTaxRate", s != null && s.getIncomeTaxRate() != null ? s.getIncomeTaxRate() : bd("0.25"));
        settings.put("settleDay", s != null && s.getSettleDay() != null ? s.getSettleDay() : 5);
        settings.put("commissionPayDay", s != null && s.getCommissionPayDay() != null ? s.getCommissionPayDay() : 10);
        settings.put("reconcileTn", s != null && s.getReconcileTn() != null ? s.getReconcileTn() : 1);
        settings.put("diffThresholdYuan", s != null && s.getDiffThreshold() != null ? yuan(s.getDiffThreshold()) : 100);
        settings.put("mirrorKingdee", s == null || s.getMirrorKingdee() == null || s.getMirrorKingdee());
        settings.put("mirrorYonyou", s != null && Boolean.TRUE.equals(s.getMirrorYonyou()));
        settings.put("outboxRetry", s != null && s.getOutboxRetry() != null ? s.getOutboxRetry() : 3);

        Map<String, Boolean> enableMap = new LinkedHashMap<>();
        subjectRepo.findAllByOrderByEnableIdAsc().forEach(x -> enableMap.put(x.getCode(), x.getEnabled()));
        List<Map<String, Object>> subjects = new ArrayList<>();
        for (String code : SUBJECT_CODES) {
            boolean enabled = enableMap.getOrDefault(code, !"RF-RECEIVABLE".equals(code));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", SUBJECT_LABEL.get(code));
            m.put("enabled", enabled);
            subjects.add(m);
        }

        List<Map<String, Object>> logs = new ArrayList<>();
        for (FinChangeLog l : changeLogRepo.findTop50ByOrderByLogAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getLogId());
            m.put("by", l.getLogBy());
            m.put("at", l.getLogAt());
            m.put("field", l.getFieldLabel());
            m.put("oldValue", l.getOldValue());
            m.put("newValue", l.getNewValue());
            logs.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings);
        out.put("subjects", subjects);
        out.put("logs", logs);
        return out;
    }

    /** 保存设置：逐字段 diff 落 fin_change_log；科目开关 diff 同样落日志；全审计。 */
    @Transactional
    public Map<String, Object> saveSettings(Map<String, Object> body, String actor) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "设置内容不能为空");
        }
        OffsetDateTime now = OffsetDateTime.now();
        FinSetting s = settingRepo.findById(SETTING_ID).orElseGet(FinSetting::new);
        if (s.getSettingId() == null) s.setSettingId(SETTING_ID);

        BigDecimal oldVat = nz(s.getVatRate(), bd("0.06"));
        BigDecimal oldSurtax = nz(s.getSurtaxRate(), bd("0.12"));
        BigDecimal oldIncome = nz(s.getIncomeTaxRate(), bd("0.25"));
        int oldSettle = nz(s.getSettleDay(), 5);
        int oldCommission = nz(s.getCommissionPayDay(), 10);
        int oldTn = nz(s.getReconcileTn(), 1);
        long oldThreshold = nz(s.getDiffThreshold(), 10000L);
        boolean oldKingdee = s.getMirrorKingdee() == null || s.getMirrorKingdee();
        boolean oldYonyou = Boolean.TRUE.equals(s.getMirrorYonyou());
        int oldRetry = nz(s.getOutboxRetry(), 3);

        BigDecimal vat = parseRate(body.get("vatRate"));
        BigDecimal surtax = parseRate(body.get("surtaxRate"));
        BigDecimal income = parseRate(body.get("incomeTaxRate"));
        int settle = parseIntRange(body.get("settleDay"), "门店结算日", 1, 28);
        int commission = parseIntRange(body.get("commissionPayDay"), "专家提成发放日", 1, 28);
        int tn = parseIntRange(body.get("reconcileTn"), "对账周期 T+N", 0, 30);
        long thresholdFen = parsePositiveFen(body.get("diffThreshold"), "对账差异阈值");
        boolean kingdee = parseBool(body.get("mirrorKingdee"), "金蝶镜像开关");
        boolean yonyou = parseBool(body.get("mirrorYonyou"), "用友镜像开关");
        int retry = parseIntRange(body.get("outboxRetry"), "Outbox 重试次数", 0, 10);

        List<FinChangeLog> logs = new ArrayList<>();
        if (vat.compareTo(oldVat) != 0) logs.add(log(actor, now, "增值税率", pct(oldVat), pct(vat)));
        if (surtax.compareTo(oldSurtax) != 0) logs.add(log(actor, now, "附加税率", pct(oldSurtax), pct(surtax)));
        if (income.compareTo(oldIncome) != 0) logs.add(log(actor, now, "所得税率", pct(oldIncome), pct(income)));
        if (settle != oldSettle) logs.add(log(actor, now, "门店结算日", "每月 " + oldSettle + " 号", "每月 " + settle + " 号"));
        if (commission != oldCommission) logs.add(log(actor, now, "专家提成发放日", "每月 " + oldCommission + " 号", "每月 " + commission + " 号"));
        if (tn != oldTn) logs.add(log(actor, now, "对账周期 T+N", "T+" + oldTn, "T+" + tn));
        if (thresholdFen != oldThreshold) logs.add(log(actor, now, "对账差异阈值", money(oldThreshold), money(thresholdFen)));
        if (kingdee != oldKingdee) logs.add(log(actor, now, "金蝶镜像源", onOff(oldKingdee), onOff(kingdee)));
        if (yonyou != oldYonyou) logs.add(log(actor, now, "用友镜像源", onOff(oldYonyou), onOff(yonyou)));
        if (retry != oldRetry) logs.add(log(actor, now, "Outbox 重试次数", String.valueOf(oldRetry), String.valueOf(retry)));

        s.setVatRate(vat);
        s.setSurtaxRate(surtax);
        s.setIncomeTaxRate(income);
        s.setSettleDay(settle);
        s.setCommissionPayDay(commission);
        s.setReconcileTn(tn);
        s.setDiffThreshold(thresholdFen);
        s.setMirrorKingdee(kingdee);
        s.setMirrorYonyou(yonyou);
        s.setOutboxRetry(retry);
        s.setUpdatedAt(now);
        s.setUpdatedBy(actor);
        settingRepo.save(s);

        int subjectChanged = 0;
        Object subjObj = body.get("subjects");
        if (subjObj instanceof List<?> rawList) {
            for (Object o : rawList) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String code = str(m.get("code"));
                Object en = m.get("enabled");
                if (code == null || en == null || !SUBJECT_CODES.contains(code)) continue;
                boolean want = Boolean.TRUE.equals(en) || "true".equalsIgnoreCase(String.valueOf(en));
                FinSubjectEnable row = subjectRepo.findByCode(code).orElseGet(FinSubjectEnable::new);
                boolean old = row.getEnabled() == null ? !"RF-RECEIVABLE".equals(code) : row.getEnabled();
                if (row.getCode() == null) row.setCode(code);
                row.setEnabled(want);
                row.setUpdatedAt(now);
                subjectRepo.save(row);
                if (old != want) {
                    subjectChanged++;
                    logs.add(log(actor, now, "科目「" + SUBJECT_LABEL.get(code) + "」", onOff(old), onOff(want)));
                }
            }
        }
        if (!logs.isEmpty()) changeLogRepo.saveAll(logs);

        int changed = logs.size();
        if (changed > 0) {
            audit("FIN_SETTING", "SETTING", actor, "SAVE",
                    Map.of("fieldChanged", logs.size() - subjectChanged, "subjectChanged", subjectChanged));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changed", changed);
        out.put("subjectChanged", subjectChanged);
        return out;
    }

    // ==================== 工具 ====================

    private FinInvoice mustInvoice(Long id) {
        return invoiceRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "发票不存在：" + id));
    }

    /** 票号 INV-yyyyMMdd(北京)-0001，序号按当日已有票号递增（synchronized 防并发重号）。 */
    private synchronized String nextInvoiceNo() {
        String day = LocalDate.now(CN).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "INV-" + day + "-";
        int seq = invoiceRepo.maxSeqOfDay(prefix + "%") + 1;
        return prefix + String.format("%04d", seq);
    }

    /** 税额 = 价税合计 - 价税合计 /（1+税率），四舍五入到分；税率 0 时税额 0。 */
    private long computeTaxFen(long amountFen, BigDecimal rate) {
        if (rate.signum() == 0) return 0L;
        BigDecimal divisor = BigDecimal.ONE.add(rate);
        BigDecimal net = new BigDecimal(amountFen).divide(divisor, 0, RoundingMode.HALF_UP);
        return amountFen - net.longValue();
    }

    private BigDecimal parseRate(Object v) {
        if (v == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "税率不能为空");
        BigDecimal r;
        try {
            r = new BigDecimal(String.valueOf(v).trim()).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "税率格式不合法：" + v);
        }
        if (r.signum() < 0 || r.compareTo(BigDecimal.ONE) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "税率须在 0~1 之间");
        }
        return r;
    }

    private BigDecimal parseInvoiceRate(Object v) {
        BigDecimal r = parseRate(v);
        if (!INVOICE_RATES.contains(r)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "税率须为允许档位（0 / 0.01 / 0.03 / 0.06 / 0.13）");
        }
        return r;
    }

    private long parsePositiveFen(Object v, String label) {
        if (v == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "不能为空");
        BigDecimal yuan;
        try {
            yuan = new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "格式不合法：" + v);
        }
        if (yuan.signum() < 0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "不能为负");
        return yuan.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private int parseIntRange(Object v, String label, int min, int max) {
        if (v == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "不能为空");
        int n;
        try {
            n = new BigDecimal(String.valueOf(v).trim()).intValue();
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "格式不合法：" + v);
        }
        if (n < min || n > max) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "须在 " + min + "~" + max + " 之间");
        }
        return n;
    }

    private boolean parseBool(Object v, String label) {
        if (v instanceof Boolean b) return b;
        if (v == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "不能为空");
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.equals("true") || s.equals("1")) return true;
        if (s.equals("false") || s.equals("0")) return false;
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "格式不合法：" + v);
    }

    private String normalizeRefs(Object v) {
        if (v == null) return null;
        List<String> refs = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) refs.add(String.valueOf(o).trim());
            }
        } else {
            for (String p : String.valueOf(v).split(",")) {
                if (!p.isBlank()) refs.add(p.trim());
            }
        }
        if (refs.isEmpty()) return null;
        String joined = String.join(",", refs);
        return truncate(joined, 512);
    }

    private Map<String, Object> invoiceView(FinInvoice i, Map<String, String> storeNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getInvoiceId());
        m.put("invoiceNo", i.getInvoiceNo());
        m.put("type", i.getType());
        m.put("category", i.getCategory());
        m.put("title", i.getTitle());
        m.put("taxNo", i.getTaxNo() == null ? "" : i.getTaxNo());
        m.put("amount", yuan(i.getAmount()));
        m.put("taxAmount", yuan(i.getTaxAmount()));
        m.put("taxRate", i.getTaxRate());
        m.put("buyerName", i.getBuyerName());
        m.put("orderRefs", i.getOrderRefs() == null ? List.of() : List.of(i.getOrderRefs().split(",")));
        m.put("storeCode", i.getStoreCode());
        m.put("store", storeNames.getOrDefault(i.getStoreCode(), i.getStoreCode()));
        m.put("status", i.getStatus());
        m.put("issuedAt", i.getIssuedAt());
        m.put("operator", i.getOperator());
        m.put("reviewer", i.getReviewer());
        m.put("remark", i.getRemark());
        return m;
    }

    private FinChangeLog log(String actor, OffsetDateTime at, String field, String oldV, String newV) {
        FinChangeLog l = new FinChangeLog();
        l.setLogBy(actor == null ? "system" : actor);
        l.setLogAt(at);
        l.setFieldLabel(truncate(field, 128));
        l.setOldValue(truncate(oldV, 256));
        l.setNewValue(truncate(newV, 256));
        return l;
    }

    private void audit(String bizType, String txnNo, String actor, String action, Object payload) {
        try {
            audit.record(bizType, txnNo, actor == null ? "system" : actor, action, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("审计序列化失败 bizType={} txnNo={} : {}", bizType, txnNo, e.getMessage());
        }
    }

    private static double yuan(Long fen) {
        if (fen == null) return 0.0;
        return new BigDecimal(fen).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String money(long fen) {
        return "¥" + new BigDecimal(fen).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static String pct(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "%";
    }

    private static String onOff(boolean b) {
        return b ? "开启" : "关闭";
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s).setScale(4, RoundingMode.HALF_UP);
    }

    private static <T> T nz(T v, T dft) {
        return v == null ? dft : v;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String updatedByOf(List<FinBudget> rows, String code) {
        for (FinBudget b : rows) if (code.equals(b.getSubjectCode())) return b.getUpdatedBy();
        return null;
    }

    /** 空库首次回落默认预算（元），与前端 finBudget 默认值一致。 */
    private double defaultBudgetYuan(String code) {
        return switch (code) {
            case "REVENUE" -> 60000;
            case "COST" -> 12000;
            case "MATERIAL" -> 2000;
            case "LABOR" -> 10000;
            case "DEPRECIATION" -> 1500;
            case "LOSS" -> 1000;
            case "MARKETING" -> 5000;
            case "RENT" -> 8000;
            default -> 0;
        };
    }
}
