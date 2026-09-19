package com.meiyun.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 增值税申报期登记簿后端（B63 卡4 L86，V39 fin_tax_period）。
 *
 * <p>对税局的增值税申报期，与 V7 settlement_period（内部对账封账期）语义不同：税务申报不封业务账。
 * 期间行懒创建（ensure 幂等 upsert，零 Flyway 种子）；状态机 OPEN → FILED/LATE_FILED → AMENDED，
 * CLOSED 为归档终态（本卡不做关闭动作）。申报（file）对五金额做快照，后续发票变动不改历史申报；
 * 期间一经申报（非 OPEN）即不再受理新抵扣（仿发票云「已生成统计表不得勾选」）。
 *
 * <p>截止日按税总办征科函〔2025〕64 号 2026 年度顺延日历；金额 Long「分」，视图返回「元」。
 */
@Service
public class TaxPeriodService {

    private static final Logger log = LoggerFactory.getLogger(TaxPeriodService.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);
    private static final Set<String> TYPES = Set.of("MONTH", "QUARTER");
    private static final Pattern MONTH_PATTERN = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final Pattern QUARTER_PATTERN = Pattern.compile("\\d{4}-Q[1-4]");
    private static final int PAGE_MAX = 100;

    /** 2026 年度按月申报顺延截止日（税总办征科函〔2025〕64 号），键为所属期 yyyy-MM。 */
    private static final Map<String, LocalDate> MONTHLY_DEADLINE_2026 = Map.ofEntries(
            Map.entry("2026-01", LocalDate.of(2026, 2, 24)),
            Map.entry("2026-02", LocalDate.of(2026, 3, 16)),
            Map.entry("2026-03", LocalDate.of(2026, 4, 20)),
            Map.entry("2026-04", LocalDate.of(2026, 5, 22)),
            Map.entry("2026-05", LocalDate.of(2026, 6, 15)),
            Map.entry("2026-06", LocalDate.of(2026, 7, 15)),
            Map.entry("2026-07", LocalDate.of(2026, 8, 17)),
            Map.entry("2026-08", LocalDate.of(2026, 9, 15)),
            Map.entry("2026-09", LocalDate.of(2026, 10, 26)),
            Map.entry("2026-10", LocalDate.of(2026, 11, 16)),
            Map.entry("2026-11", LocalDate.of(2026, 12, 15)),
            Map.entry("2026-12", LocalDate.of(2027, 1, 20)));

    /** 2026 年度按季申报顺延截止日，键为所属期 yyyy-Qn。 */
    private static final Map<String, LocalDate> QUARTERLY_DEADLINE_2026 = Map.of(
            "2026-Q1", LocalDate.of(2026, 4, 20),
            "2026-Q2", LocalDate.of(2026, 7, 15),
            "2026-Q3", LocalDate.of(2026, 10, 26),
            "2026-Q4", LocalDate.of(2027, 1, 20));

    private final FinTaxPeriodRepository periodRepo;
    private final FinInputInvoiceRepository inputRepo;
    private final FinInvoiceRepository invoiceRepo;
    private final FinanceAuditRecorder audit;
    private final ObjectMapper objectMapper;

    public TaxPeriodService(FinTaxPeriodRepository periodRepo, FinInputInvoiceRepository inputRepo,
                            FinInvoiceRepository invoiceRepo, FinanceAuditRecorder audit,
                            ObjectMapper objectMapper) {
        this.periodRepo = periodRepo;
        this.inputRepo = inputRepo;
        this.invoiceRepo = invoiceRepo;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    // ==================== 期间查询与懒创建 ====================

    /** 期间列表（分页，元）；可按 type/year 过滤，按税款起始日倒序。 */
    public Page<Map<String, Object>> list(String type, Integer year, int page, int size) {
        String periodType = normalizeType(type);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), PAGE_MAX);
        Page<FinTaxPeriod> rows = periodRepo.findAll((root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("periodType"), periodType));
            if (year != null) ps.add(cb.like(root.get("period"), year + "-%"));
            return cb.and(ps.toArray(new Predicate[0]));
        }, PageRequest.of(p, s, Sort.by(Sort.Order.desc("periodStart"), Sort.Order.desc("id"))));
        return rows.map(this::periodViewWithLive);
    }

    /**
     * 当前开放期（元）。type 缺省 MONTH；存在则返回（OPEN 期附实时五金额预览，已申报期读快照），
     * 不存在返回 200＋exists=false 的预期期间预填体（前端据此引导 ensure，避免 Promise 404）。
     */
    public Map<String, Object> current(String type) {
        String periodType = normalizeType(type);
        LocalDate today = LocalDate.now(CN);
        Optional<FinTaxPeriod> found = findOpenByDay(periodType, today);
        if (found.isPresent()) return periodViewWithLive(found.get());
        String label = currentLabel(periodType, today);
        Map<String, Object> v = periodSkeleton(periodType, label);
        v.put("exists", false);
        return v;
    }

    /** 幂等确保期间存在（body：periodType/period 均缺省当前期）；并发唯一索引冲突时回落重查。 */
    @Transactional
    public Map<String, Object> ensure(Map<String, Object> body, String actor) {
        String periodType = normalizeType(str(body == null ? null : body.get("periodType")));
        String label = str(body == null ? null : body.get("period"));
        if (label == null || label.isBlank()) {
            label = currentLabel(periodType, LocalDate.now(CN));
        }
        validateLabel(periodType, label);
        Optional<FinTaxPeriod> found = findOpen(periodType, label);
        if (found.isPresent()) return periodViewWithLive(found.get());
        FinTaxPeriod p = new FinTaxPeriod();
        p.setPeriodType(periodType);
        p.setPeriod(label);
        p.setPeriodStart(rangeStart(periodType, label));
        p.setPeriodEnd(rangeEnd(periodType, label));
        p.setDeadline(deadlineOf(periodType, label));
        p.setStatus("OPEN");
        p.setCreatedBy(actor == null ? "system" : truncate(actor, 16));
        p.setUpdatedBy(actor == null ? "system" : truncate(actor, 16));
        try {
            periodRepo.save(p);
        } catch (DataIntegrityViolationException e) {
            Optional<FinTaxPeriod> again = findOpen(periodType, label);
            if (again.isPresent()) return periodViewWithLive(again.get());
            throw e;
        }
        return periodViewWithLive(p);
    }

    /** 抵扣动作解析归属期：periodId 传则校验为 OPEN；不传则懒创建当前月期。非 OPEN 一律中文 422。 */
    @Transactional
    public FinTaxPeriod resolveOpenPeriodForDeduct(Long periodId, String actor) {
        if (periodId != null) {
            FinTaxPeriod p = mustPeriod(periodId);
            if (!"OPEN".equals(p.getStatus())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "申报期已申报（" + periodStatusLabel(p.getStatus()) + "），期间一经申报不再受理新抵扣");
            }
            return p;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodType", "MONTH");
        Map<String, Object> view = ensure(body, actor);
        FinTaxPeriod p = mustPeriod(((Number) view.get("id")).longValue());
        if (!"OPEN".equals(p.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "申报期已申报（" + periodStatusLabel(p.getStatus()) + "），期间一经申报不再受理新抵扣");
        }
        return p;
    }

    // ==================== 申报 / 更正 ====================

    /** 申报：OPEN → FILED（按期）/LATE_FILED（逾期），五金额快照落库，锁抵扣。 */
    @Transactional
    public Map<String, Object> file(Long id, String remark, String actor) {
        FinTaxPeriod p = mustPeriod(id);
        if (!"OPEN".equals(p.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "仅未申报（OPEN）期间可申报，当前状态：" + periodStatusLabel(p.getStatus()));
        }
        long[] snap = snapshot(p);
        p.setOutputAmount(snap[0]);
        p.setInputAmount(snap[1]);
        p.setTransferOutAmount(snap[2]);
        p.setPayableAmount(snap[3]);
        p.setRetainedAmount(snap[4]);
        boolean late = LocalDate.now(CN).isAfter(p.getDeadline());
        p.setStatus(late ? "LATE_FILED" : "FILED");
        p.setFiledAt(OffsetDateTime.now());
        p.setFiler(truncate(actor, 64));
        if (remark != null && !remark.isBlank()) p.setRemark(truncate(remark.trim(), 256));
        p.setUpdatedBy(actor == null ? "system" : truncate(actor, 16));
        periodRepo.save(p);
        audit("FIN_TAX_PERIOD", p.getPeriodType() + ":" + p.getPeriod(), actor, "FILE",
                Map.of("periodId", p.getId(), "period", p.getPeriod(), "status", p.getStatus(),
                        "outputYuan", yuan(snap[0]), "inputYuan", yuan(snap[1]),
                        "transferOutYuan", yuan(snap[2]), "payableYuan", yuan(snap[3]),
                        "retainedYuan", yuan(snap[4]), "deadline", p.getDeadline().toString()));
        return periodViewWithLive(p);
    }

    /** 更正申报：FILED/LATE_FILED/AMENDED → AMENDED，快照原值不改，仅备注追加留痕。 */
    @Transactional
    public Map<String, Object> amend(Long id, String note, String actor) {
        FinTaxPeriod p = mustPeriod(id);
        if (List.of("FILED", "LATE_FILED", "AMENDED").contains(p.getStatus())) {
            if (note == null || note.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "更正申报说明不能为空");
            }
            String merged = (p.getRemark() == null ? "" : p.getRemark() + " ｜ ")
                    + "更正：" + note.trim();
            p.setRemark(truncate(merged, 256));
            p.setStatus("AMENDED");
            p.setUpdatedBy(actor == null ? "system" : truncate(actor, 16));
            periodRepo.save(p);
            audit("FIN_TAX_PERIOD", p.getPeriodType() + ":" + p.getPeriod(), actor, "AMEND",
                    Map.of("periodId", p.getId(), "period", p.getPeriod(), "note", note.trim()));
            return periodViewWithLive(p);
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "仅已申报期间可更正申报，当前状态：" + periodStatusLabel(p.getStatus()));
    }

    // ==================== 快照与视图 ====================

    FinTaxPeriod mustPeriod(Long id) {
        return periodRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "申报期不存在：" + id));
    }

    Optional<FinTaxPeriod> findOpenByDay(String periodType, LocalDate day) {
        List<FinTaxPeriod> list = periodRepo.findOpenContaining(periodType, day);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    Optional<FinTaxPeriod> findOpen(String periodType, String label) {
        return periodRepo.findFirstByPeriodTypeAndPeriodAndStatusNot(
                periodType, label, "CLOSED", Sort.by(Sort.Order.desc("id")));
    }

    /**
     * 五金额快照（分）：[output, input 净可抵扣进项, transferOut, payable, retained]。
     * 净可抵扣进项＝归属期内 DEDUCTED/TRANSFERRED_OUT 票税额合计 − 进项转出额；
     * 应纳＝max(0, 销项−净进项)，留抵＝max(0, 净进项−销项)。销项取期间内 ISSUED 票税额。
     */
    long[] snapshot(FinTaxPeriod p) {
        long output = invoiceRepo.sumIssuedTaxBetween(
                p.getPeriodStart().atTime(LocalTime.MIN).atOffset(OFFSET),
                p.getPeriodEnd().plusDays(1).atTime(LocalTime.MIN).atOffset(OFFSET));
        long deductedTax = inputRepo.sumTaxByPeriodAndStatuses(
                p.getId(), List.of("DEDUCTED", "TRANSFERRED_OUT"));
        long transferOut = inputRepo.sumTransferOutByPeriod(p.getId());
        long netInput = Math.max(0L, deductedTax - transferOut);
        long payable = Math.max(0L, output - netInput);
        long retained = Math.max(0L, netInput - output);
        return new long[]{output, netInput, transferOut, payable, retained};
    }

    /** 期间视图（元）：已申报读库内快照；OPEN 期附实时五金额预览（不落库）。 */
    private Map<String, Object> periodViewWithLive(FinTaxPeriod p) {
        Map<String, Object> m = periodSkeleton(p.getPeriodType(), p.getPeriod());
        m.put("id", p.getId());
        m.put("exists", true);
        m.put("status", p.getStatus());
        m.put("statusLabel", periodStatusLabel(p.getStatus()));
        m.put("filedAt", p.getFiledAt());
        m.put("filer", p.getFiler());
        m.put("remark", p.getRemark());
        m.put("createdAt", p.getCreatedAt());
        m.put("updatedAt", p.getUpdatedAt());
        long[] snap;
        boolean filed = !"OPEN".equals(p.getStatus());
        if (filed) {
            snap = new long[]{p.getOutputAmount(), p.getInputAmount(), p.getTransferOutAmount(),
                    p.getPayableAmount(), p.getRetainedAmount()};
        } else {
            snap = snapshot(p);
        }
        m.put("outputAmount", yuan(snap[0]));
        m.put("inputAmount", yuan(snap[1]));
        m.put("transferOutAmount", yuan(snap[2]));
        m.put("payableAmount", yuan(snap[3]));
        m.put("retainedAmount", yuan(snap[4]));
        m.put("snapshot", filed);
        return m;
    }

    /** 未登记期间的骨架（含起止/截止日历），供 GET 汇总零金额场景复用，不触发懒创建。 */
    Map<String, Object> blankPeriodView(String periodType, String label) {
        return periodSkeleton(periodType, label);
    }

    private Map<String, Object> periodSkeleton(String periodType, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("periodType", periodType);
        m.put("period", label);
        LocalDate start = rangeStart(periodType, label);
        LocalDate end = rangeEnd(periodType, label);
        m.put("periodStart", start);
        m.put("periodEnd", end);
        m.put("deadline", deadlineOf(periodType, label));
        return m;
    }

    // ==================== 期间日历 ====================

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) return "MONTH";
        String t = type.trim().toUpperCase();
        if (!TYPES.contains(t)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "申报频率不合法（MONTH/QUARTER）");
        }
        return t;
    }

    private void validateLabel(String periodType, String label) {
        boolean ok = "MONTH".equals(periodType)
                ? MONTH_PATTERN.matcher(label).matches()
                : QUARTER_PATTERN.matcher(label).matches();
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "MONTH".equals(periodType) ? "月度期间标识格式须为 yyyy-MM（如 2026-08）"
                            : "季度期间标识格式须为 yyyy-Qn（如 2026-Q3）");
        }
    }

    private String currentLabel(String periodType, LocalDate today) {
        if ("QUARTER".equals(periodType)) {
            return today.getYear() + "-Q" + ((today.getMonthValue() - 1) / 3 + 1);
        }
        return today.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private LocalDate rangeStart(String periodType, String label) {
        if ("QUARTER".equals(periodType)) {
            int year = Integer.parseInt(label.substring(0, 4));
            int q = Integer.parseInt(label.substring(6));
            return LocalDate.of(year, (q - 1) * 3 + 1, 1);
        }
        return LocalDate.parse(label + "-01");
    }

    private LocalDate rangeEnd(String periodType, String label) {
        if ("QUARTER".equals(periodType)) {
            int year = Integer.parseInt(label.substring(0, 4));
            int q = Integer.parseInt(label.substring(6));
            return LocalDate.of(year, q * 3, 1)
                    .plusMonths(1).minusDays(1);
        }
        LocalDate start = LocalDate.parse(label + "-01");
        return start.plusMonths(1).minusDays(1);
    }

    /** 申报截止日：优先取 2026 顺延日历，无登记时月度次月 15 日、季度季后首月 15 日兜底。 */
    private LocalDate deadlineOf(String periodType, String label) {
        if ("QUARTER".equals(periodType)) {
            LocalDate d = QUARTERLY_DEADLINE_2026.get(label);
            if (d != null) return d;
            LocalDate firstAfter = rangeEnd(periodType, label).plusDays(1);
            return LocalDate.of(firstAfter.getYear(), firstAfter.getMonth(), 15);
        }
        LocalDate d = MONTHLY_DEADLINE_2026.get(label);
        if (d != null) return d;
        LocalDate start = rangeStart(periodType, label);
        LocalDate next = start.plusMonths(1);
        return LocalDate.of(next.getYear(), next.getMonth(), 15);
    }

    static String periodStatusLabel(String status) {
        return switch (status) {
            case "OPEN" -> "未申报";
            case "FILED" -> "已申报";
            case "LATE_FILED" -> "逾期补申报";
            case "AMENDED" -> "更正申报";
            case "CLOSED" -> "已归档";
            default -> status;
        };
    }

    // ==================== 工具 ====================

    private void audit(String bizType, String txnNo, String actor, String action, Object payload) {
        try {
            audit.record(bizType, txnNo, actor == null ? "system" : actor, action,
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("审计序列化失败 bizType={} txnNo={} : {}", bizType, txnNo, e.getMessage());
        }
    }

    private static double yuan(Long fen) {
        if (fen == null) return 0.0;
        return new BigDecimal(fen).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    static LocalDate parseDate(Object v, String label) {
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "不能为空");
        }
        try {
            return LocalDate.parse(String.valueOf(v).trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    label + "日期格式不合法（须为 yyyy-MM-dd）：" + v);
        }
    }
}
