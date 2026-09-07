package com.meiyun.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.AuthInterceptor;
import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 薪酬提成域服务（B9）：提成规则/员工薪酬配置维护 + 月度提成单试算生成与审批状态机。
 *
 * <p><b>试算</b>：超额累进阶梯（个税式分段，跨档只对超出部分按高档），金额 Long「分」、
 * rate 万分位，严格对齐前端 finCommission.ts calcTiers 语义（amount == 档下限不入该档）。
 * <b>业绩取数</b>：跨 txn 域内部端点 /api/txn/internal/commission-base（X-Internal-Token），
 * 失败降级空集合（中文日志，不臆造业绩）。<b>红线</b>：提成只写 commission_record 成本镜像，
 * 绝不在 fund_entry 生成实付分录；PAID 仅镜像外部薪酬系统回传状态。
 */
@Service
public class CommissionService {

    private static final Logger log = LoggerFactory.getLogger(CommissionService.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final CommissionRuleRepository ruleRepo;
    private final StaffCompConfigRepository compRepo;
    private final CommissionRecordRepository recordRepo;
    private final CommissionNoGenerator noGenerator;
    private final FinanceAuditRecorder audit;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${txn.service.url:http://127.0.0.1:8083}")
    private String txnBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public CommissionService(CommissionRuleRepository ruleRepo, StaffCompConfigRepository compRepo,
                             CommissionRecordRepository recordRepo, CommissionNoGenerator noGenerator,
                             FinanceAuditRecorder audit, RestTemplate restTemplate) {
        this.ruleRepo = ruleRepo;
        this.compRepo = compRepo;
        this.recordRepo = recordRepo;
        this.noGenerator = noGenerator;
        this.audit = audit;
        this.restTemplate = restTemplate;
    }

    // ==================== 提成规则 ====================

    public List<CommissionRule> listRules() {
        return ruleRepo.findAllByOrderByCreatedAtDesc();
    }

    /** 新建提成规则（校验阶梯：rate 万分位 0-10000、min 分非负且严格递增）。 */
    @Transactional
    public CommissionRule createRule(String name, String base, String role, List<Tier> tiers, String actor) {
        validateRuleName(name);
        validateBaseRole(base, role);
        String tiersJson = validateAndNormalizeTiers(tiers);
        CommissionRule rule = new CommissionRule();
        rule.setRuleId(noGenerator.nextRuleNo());
        rule.setRuleName(name.trim());
        rule.setBase(base);
        rule.setRole(role);
        rule.setTiersJson(tiersJson);
        rule.setActive(true);
        rule.setCreatedBy(actor);
        rule.setUpdatedBy(actor);
        ruleRepo.save(rule);
        audit.record("FIN_COMM", rule.getRuleId(), actor, "RULE_CREATE",
                json(Map.of("ruleName", rule.getRuleName(), "base", base, "role", role, "tiers", tiers)));
        return rule;
    }

    /** 规则改名/改阶梯/启停（停用不影响已生成提成单；变更不追溯历史期间）。 */
    @Transactional
    public CommissionRule updateRule(String ruleId, String name, Boolean active, List<Tier> tiers, String actor) {
        CommissionRule rule = ruleRepo.findById(ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "提成规则不存在：" + ruleId));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("ruleName", rule.getRuleName());
        before.put("active", rule.isActive());
        if (name != null && !name.isBlank()) {
            validateRuleName(name);
            rule.setRuleName(name.trim());
        }
        if (tiers != null && !tiers.isEmpty()) {
            rule.setTiersJson(validateAndNormalizeTiers(tiers));
        }
        if (active != null) rule.setActive(active);
        rule.setUpdatedBy(actor);
        rule.setUpdatedAt(OffsetDateTime.now());
        ruleRepo.save(rule);
        audit.record("FIN_COMM", rule.getRuleId(), actor, "RULE_UPDATE",
                json(Map.of("before", before, "ruleName", rule.getRuleName(), "active", rule.isActive())));
        return rule;
    }

    // ==================== 员工薪酬配置 ====================

    public List<StaffCompConfig> listCompConfigs(String storeCode) {
        List<StaffCompConfig> actives = compRepo.findByStatusOrderByStaffIdAsc("ACTIVE");
        List<StaffCompConfig> out = new ArrayList<>();
        for (StaffCompConfig c : actives) {
            if (storeCode != null && !storeCode.isBlank() && !storeCode.equals(c.getStoreCode())) continue;
            if (!DataScope.canReadStore(c.getStoreCode())) continue;
            out.add(c);
        }
        return out;
    }

    /**
     * 保存员工薪酬配置（底薪 + 适用规则 + 生效月）。
     * 调薪 = 该员工旧 ACTIVE 单置 INACTIVE + 新单 ACTIVE（不追溯历史期间）。
     */
    @Transactional
    public StaffCompConfig saveCompConfig(String staffId, String staffName, String storeCode,
                                          Long baseSalary, String commissionRuleId,
                                          LocalDate effectiveMonth, String actor) {
        if (staffId == null || staffId.isBlank()) throw bad("工号不能为空");
        if (staffName == null || staffName.isBlank()) throw bad("员工姓名不能为空");
        if (storeCode == null || storeCode.isBlank()) throw bad("所属门店不能为空");
        if (baseSalary == null || baseSalary < 0) throw bad("月底薪必须为不小于 0 的金额（分）");
        if (baseSalary > 100_000_000L) throw bad("月底薪金额超出合理范围");
        if (commissionRuleId != null && !commissionRuleId.isBlank()) {
            final String requestedRuleId = commissionRuleId.trim();
            CommissionRule rule = ruleRepo.findByRuleIdAndActiveTrue(requestedRuleId)
                    .orElseThrow(() -> bad("适用提成规则不存在或已停用：" + requestedRuleId));
            commissionRuleId = rule.getRuleId();
        } else {
            commissionRuleId = null;
        }
        LocalDate eff = effectiveMonth == null ? LocalDate.now().withDayOfMonth(1) : effectiveMonth.withDayOfMonth(1);

        compRepo.findByStaffIdAndStatus(staffId.trim(), "ACTIVE").ifPresent(old -> {
            old.setStatus("INACTIVE");
            old.setUpdatedBy(actor);
            old.setUpdatedAt(OffsetDateTime.now());
            compRepo.save(old);
        });

        StaffCompConfig c = new StaffCompConfig();
        c.setCompId(noGenerator.nextCompNo());
        c.setStaffId(staffId.trim());
        c.setStaffName(staffName.trim());
        c.setStoreCode(storeCode.trim());
        c.setBaseSalary(baseSalary);
        c.setCommissionRuleId(commissionRuleId);
        c.setEffectiveMonth(eff);
        c.setStatus("ACTIVE");
        c.setCreatedBy(actor);
        c.setUpdatedBy(actor);
        compRepo.save(c);
        audit.record("FIN_COMP", c.getCompId(), actor, "COMP_SAVE",
                json(Map.of("staffId", c.getStaffId(), "staffName", c.getStaffName(),
                        "storeCode", c.getStoreCode(), "baseSalary", baseSalary,
                        "ruleId", commissionRuleId == null ? "" : commissionRuleId,
                        "effectiveMonth", eff.toString())));
        return c;
    }

    // ==================== 提成单：生成 / 状态机 ====================

    /**
     * 按月生成试算单（幂等）：拉 txn 当月按工号聚合业绩，对每个有 ACTIVE 薪酬配置的员工
     * 按其规则试算；同月同人已存在则重算覆盖（PAID 保持状态不改，其余回 DRAFT）。
     * 返回当月本登录人门店域可见的全部提成单。
     */
    @Transactional
    public List<CommissionRecord> generate(LocalDate period, String actor) {
        LocalDate month = period.withDayOfMonth(1);
        Map<String, BaseRow> bases = fetchCommissionBase(month);
        List<StaffCompConfig> configs = compRepo.findByStatusOrderByStaffIdAsc("ACTIVE");

        int created = 0, updated = 0, skipped = 0;
        for (StaffCompConfig cfg : configs) {
            if (!DataScope.canReadStore(cfg.getStoreCode())) continue;
            BaseRow base = bases.getOrDefault(cfg.getStaffId(), BaseRow.ZERO);
            CommissionRule rule = cfg.getCommissionRuleId() == null ? null
                    : ruleRepo.findById(cfg.getCommissionRuleId()).orElse(null);

            Optional<CommissionRecord> existing = recordRepo.findByPeriodAndStaffId(month, cfg.getStaffId());
            if (existing.isPresent() && "PAID".equals(existing.get().getStatus())) {
                skipped++;
                continue; // 已发放单锁定，不重算（资金红线）
            }

            // 按规则基数口径取业绩分量（退款扣回：按顾问负向冲减；RECHARGE 一期预留取 0）
            long baseAmount = rule == null ? 0L : base.amountForBase(rule.getBase());
            int baseCount = rule == null ? 0 : base.countForBase(rule.getBase());

            CalcResult calc = rule == null
                    ? new CalcResult(0L, List.of())
                    : calc(rule.getTiersJson(), Math.max(0L, baseAmount));

            CommissionRecord rec = existing.orElseGet(CommissionRecord::new);
            if (existing.isEmpty()) {
                rec.setRecordId(noGenerator.commissionNo(month, cfg.getStaffId()));
                rec.setPeriod(month);
                rec.setStaffId(cfg.getStaffId());
                rec.setCreatedAt(OffsetDateTime.now());
                created++;
            } else {
                if (!"PAID".equals(rec.getStatus())) rec.setStatus("DRAFT");
                updated++;
            }
            rec.setStaffName(cfg.getStaffName());
            rec.setStoreCode(cfg.getStoreCode());
            rec.setRuleId(rule == null ? null : rule.getRuleId());
            rec.setRuleName(rule == null ? null : rule.getRuleName());
            rec.setBaseAmount(baseAmount);
            rec.setOrderCount(baseCount);
            rec.setTiersJson(json(calc.segments()));
            rec.setCommission(calc.commission());
            recordRepo.save(rec);
        }
        audit.record("FIN_COMM", "GEN-" + month, actor, "GENERATE",
                json(Map.of("period", month.toString(), "created", created, "updated", updated,
                        "skippedPaid", skipped, "baseRows", bases.size())));
        log.info("提成生成 period={} 新建={} 重算={} 跳过已发放={} 业绩行={}", month, created, updated, skipped, bases.size());
        return listRecords(month, null);
    }

    public List<CommissionRecord> listRecords(LocalDate period, String storeCode) {
        LocalDate month = period.withDayOfMonth(1);
        List<CommissionRecord> rows = storeCode != null && !storeCode.isBlank()
                ? recordRepo.findByPeriodAndStoreCodeOrderByCommissionDesc(month, storeCode)
                : recordRepo.findByPeriodOrderByCommissionDesc(month);
        List<CommissionRecord> out = new ArrayList<>();
        for (CommissionRecord r : rows) {
            if (DataScope.canReadStore(r.getStoreCode())) out.add(r);
        }
        return out;
    }

    @Transactional
    public CommissionRecord submit(String recordId, String actor) {
        CommissionRecord r = must(recordId);
        if (!"DRAFT".equals(r.getStatus()) && !"REJECTED".equals(r.getStatus())) {
            throw bad("仅「待提交/已驳回」状态的提成单可提交，当前状态：" + statusLabel(r.getStatus()));
        }
        r.setStatus("SUBMITTED");
        recordRepo.save(r);
        audit.record("FIN_COMM", r.getRecordId(), actor, "SUBMIT",
                json(Map.of("staffId", r.getStaffId(), "period", r.getPeriod().toString(),
                        "commission", r.getCommission())));
        return r;
    }

    @Transactional
    public CommissionRecord approve(String recordId, String actor) {
        CommissionRecord r = must(recordId);
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw bad("仅「待审批」状态的提成单可审批，当前状态：" + statusLabel(r.getStatus()));
        }
        r.setStatus("APPROVED");
        r.setApprover(actor);
        r.setApprovedAt(OffsetDateTime.now());
        recordRepo.save(r);
        audit.record("FIN_COMM", r.getRecordId(), actor, "APPROVE",
                json(Map.of("staffId", r.getStaffId(), "period", r.getPeriod().toString(),
                        "commission", r.getCommission())));
        return r;
    }

    @Transactional
    public CommissionRecord reject(String recordId, String reason, String actor) {
        CommissionRecord r = must(recordId);
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw bad("仅「待审批」状态的提成单可驳回，当前状态：" + statusLabel(r.getStatus()));
        }
        r.setStatus("REJECTED");
        r.setRemark(reason);
        r.setApprover(actor);
        r.setApprovedAt(OffsetDateTime.now());
        recordRepo.save(r);
        audit.record("FIN_COMM", r.getRecordId(), actor, "REJECT",
                json(Map.of("staffId", r.getStaffId(), "period", r.getPeriod().toString(),
                        "reason", reason == null ? "" : reason)));
        return r;
    }

    /** 发放登记：仅镜像外部薪酬系统已发放状态，绝不在本系统划款（资金红线）。 */
    @Transactional
    public CommissionRecord markPaid(String recordId, String actor) {
        CommissionRecord r = must(recordId);
        if (!"APPROVED".equals(r.getStatus())) {
            throw bad("仅「已审批待发放」状态的提成单可登记发放，当前状态：" + statusLabel(r.getStatus()));
        }
        r.setStatus("PAID");
        r.setPaidAt(OffsetDateTime.now());
        recordRepo.save(r);
        audit.record("FIN_COMM", r.getRecordId(), actor, "MARK_PAID",
                json(Map.of("staffId", r.getStaffId(), "period", r.getPeriod().toString(),
                        "commission", r.getCommission(), "note", "外部薪酬系统发放回传镜像，本系统不动账")));
        return r;
    }

    /** 本单提成预估（咨询页用）：按员工当前生效规则试算给定金额，不落库。 */
    public Map<String, Object> estimate(String staffId, long amount) {
        if (amount < 0) throw bad("预估金额必须为非负数（分）");
        StaffCompConfig cfg = compRepo.findByStaffIdAndStatus(staffId, "ACTIVE").orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staffId", staffId);
        out.put("amount", amount);
        if (cfg == null) {
            out.put("configured", false);
            out.put("commission", 0L);
            out.put("segments", List.of());
            return out;
        }
        out.put("configured", true);
        out.put("staffName", cfg.getStaffName());
        CommissionRule rule = cfg.getCommissionRuleId() == null ? null
                : ruleRepo.findById(cfg.getCommissionRuleId()).orElse(null);
        if (rule == null) {
            out.put("ruleId", null);
            out.put("commission", 0L);
            out.put("segments", List.of());
            return out;
        }
        CalcResult calc = calc(rule.getTiersJson(), amount);
        out.put("ruleId", rule.getRuleId());
        out.put("ruleName", rule.getRuleName());
        out.put("commission", calc.commission());
        out.put("segments", calc.segments());
        return out;
    }

    // ==================== 超额累进试算 ====================

    /**
     * 超额累进分段试算（对齐 finCommission.ts calcTiers）：
     * 逐档 i，upper=下一档 min 或无穷；amount &gt; 档下限才入该档，
     * 分段额 = min(amount, upper) - 档下限，分段提成 = 分段额 × rate（万分位），四舍五入到分。
     */
    public CalcResult calc(String tiersJson, long amount) {
        List<Tier> tiers = parseTiers(tiersJson);
        List<Map<String, Object>> segments = new ArrayList<>();
        long total = 0L;
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            long upper = (i + 1 < tiers.size()) ? tiers.get(i + 1).min() : Long.MAX_VALUE;
            if (amount > t.min()) {
                long segAmount = Math.min(amount, upper) - t.min();
                long segCommission = Math.round(segAmount * (double) t.rate() / 10000.0);
                total += segCommission;
                Map<String, Object> seg = new LinkedHashMap<>();
                seg.put("label", t.label() == null ? ("≥" + t.min() + " 分档") : t.label());
                seg.put("min", t.min());
                seg.put("amount", segAmount);
                seg.put("rate", t.rate());
                seg.put("commission", segCommission);
                segments.add(seg);
            }
        }
        return new CalcResult(total, segments);
    }

    // ==================== 跨域取数（txn 内部端点，降级空集合） ====================

    /**
     * 拉取当月按工号聚合业绩：GET txn /api/txn/internal/commission-base?month=yyyy-MM-01。
     * txn 侧返回信封 {@code {rows:[{staffId,storeCode,writeoffAmount,writeoffCount,orderAmount,orderCount,
     * refundAmount,refundCount}], unmatchedWriteoffCount, unmatchedRefundCount, unmatchedOrderCount}}，
     * 三类分量（划扣/收款/退款）随规则 base 口径取用，退款按顾问负向冲减。
     * 失败/超时降级空 Map（中文日志，不臆造业绩）；无法归属顾问的笔数仅记录日志，不摊派。
     * 金额 Long「分」。
     */
    private Map<String, BaseRow> fetchCommissionBase(LocalDate month) {
        Map<String, BaseRow> out = new LinkedHashMap<>();
        try {
            String url = UriComponentsBuilder.fromHttpUrl(txnBaseUrl + "/api/txn/internal/commission-base")
                    .queryParam("month", month.toString())
                    .toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);
            Map<String, Object> body = resp.getBody();
            Object rowsObj = body == null ? null : body.get("rows");
            if (rowsObj instanceof List<?> rows) {
                for (Object item : rows) {
                    if (!(item instanceof Map<?, ?> m)) continue;
                    String staffId = str(m.get("staffId"));
                    if (staffId == null || staffId.isBlank()) continue;
                    BaseRow row = new BaseRow(
                            num(m.get("writeoffAmount")), intVal(m.get("writeoffCount")),
                            num(m.get("orderAmount")), intVal(m.get("orderCount")),
                            num(m.get("refundAmount")), intVal(m.get("refundCount")));
                    out.merge(staffId, row, BaseRow::merge);
                }
            }
            log.info("提成业绩聚合 month={} 顾问行={} 无法归属：划扣{}笔/退款{}笔/收款{}笔", month, out.size(),
                    intVal(body == null ? null : body.get("unmatchedWriteoffCount")),
                    intVal(body == null ? null : body.get("unmatchedRefundCount")),
                    intVal(body == null ? null : body.get("unmatchedOrderCount")));
        } catch (Exception e) {
            log.warn("拉取 txn 提成业绩聚合失败，降级空业绩（不臆造）month={} : {}", month, e.getMessage());
        }
        return out;
    }

    // ==================== 校验 / 工具 ====================

    private void validateRuleName(String name) {
        if (name == null || name.isBlank()) throw bad("规则名称不能为空");
        if (name.trim().length() > 64) throw bad("规则名称不能超过 64 字");
    }

    private void validateBaseRole(String base, String role) {
        if (!List.of("WRITEOFF", "ORDER", "RECHARGE").contains(base)) {
            throw bad("基数口径必须为 WRITEOFF（划扣确认收入）/ ORDER（收款）/ RECHARGE（充值）");
        }
        if (!List.of("CONSULTANT", "DOCTOR", "BEAUTICIAN").contains(role)) {
            throw bad("适用角色必须为 CONSULTANT（咨询师）/ DOCTOR（医生）/ BEAUTICIAN（美容师）");
        }
    }

    /** 校验并归一化阶梯：rate 万分位 0-10000、min 分非负且严格递增、至少一档。 */
    private String validateAndNormalizeTiers(List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) throw bad("提成阶梯至少配置一档");
        List<Tier> sorted = tiers.stream()
                .sorted(Comparator.comparingLong(Tier::min))
                .toList();
        long prevMin = -1L;
        for (Tier t : sorted) {
            if (t.min() == null || t.min() < 0) throw bad("阶梯下限金额必须为不小于 0 的数（分）");
            if (t.rate() == null || t.rate() < 0 || t.rate() > 10000) {
                throw bad("阶梯提成率必须为 0-10000（万分位，如 600 表示 6%）");
            }
            if (t.min() <= prevMin) throw bad("阶梯下限金额必须严格递增且不重复");
            prevMin = t.min();
        }
        if (sorted.get(0).min() != 0L) throw bad("首档下限必须为 0（覆盖零业绩起算）");
        List<Map<String, Object>> norm = new ArrayList<>();
        for (Tier t : sorted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("min", t.min());
            m.put("rate", t.rate());
            if (t.label() != null) m.put("label", t.label());
            norm.add(m);
        }
        return json(norm);
    }

    private List<Tier> parseTiers(String tiersJson) {
        try {
            List<Map<String, Object>> raw = mapper.readValue(tiersJson, new TypeReference<List<Map<String, Object>>>() {});
            List<Tier> tiers = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                long min = m.get("min") instanceof Number n ? n.longValue() : 0L;
                int rate = m.get("rate") instanceof Number n ? n.intValue() : 0;
                String label = m.get("label") == null ? null : m.get("label").toString();
                tiers.add(new Tier(min, rate, label));
            }
            return tiers;
        } catch (Exception e) {
            log.error("提成规则阶梯 JSON 解析失败，按零提成处理：{}", e.getMessage());
            return List.of();
        }
    }

    private CommissionRecord must(String recordId) {
        return recordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "提成单不存在：" + recordId));
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static int intVal(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    static String statusLabel(String status) {
        return switch (status) {
            case "DRAFT" -> "待提交";
            case "SUBMITTED" -> "待审批";
            case "APPROVED" -> "已审批待发放";
            case "PAID" -> "已发放";
            case "REJECTED" -> "已驳回";
            default -> status;
        };
    }

    /** 阶梯入参/内部结构：min 下限（分）、rate 万分位、label 展示名（可空）。 */
    public record Tier(Long min, Integer rate, String label) {}

    /**
     * 业绩聚合行（按顾问，当月）：划扣/收款/退款三类分量（金额分、笔数）。
     * 退款为退款总额（正数），取基数口径时负向冲减；RECHARGE 充值口径一期预留取 0。
     */
    private record BaseRow(long writeoffAmount, int writeoffCount,
                           long orderAmount, int orderCount,
                           long refundAmount, int refundCount) {
        static final BaseRow ZERO = new BaseRow(0L, 0, 0L, 0, 0L, 0);

        BaseRow merge(BaseRow b) {
            return new BaseRow(
                    writeoffAmount + b.writeoffAmount, writeoffCount + b.writeoffCount,
                    orderAmount + b.orderAmount, orderCount + b.orderCount,
                    refundAmount + b.refundAmount, refundCount + b.refundCount);
        }

        /** 按规则基数口径取净业绩额（分）：退款按顾问负向冲减；净业绩不为负（负则按 0 试算）。 */
        long amountForBase(String base) {
            long gross = switch (base == null ? "WRITEOFF" : base) {
                case "ORDER" -> orderAmount;
                case "RECHARGE" -> 0L;
                default -> writeoffAmount;
            };
            return Math.max(0L, gross - refundAmount);
        }

        /** 按规则基数口径取业务笔数（退款笔数不冲减，仅展示单数口径）。 */
        int countForBase(String base) {
            return switch (base == null ? "WRITEOFF" : base) {
                case "ORDER" -> orderCount;
                case "RECHARGE" -> 0;
                default -> writeoffCount;
            };
        }
    }

    /** 试算结果：总提成（分）+ 分段明细。 */
    public record CalcResult(long commission, List<Map<String, Object>> segments) {}
}
