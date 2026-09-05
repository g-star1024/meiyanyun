package com.meiyun.finance;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * finance-service（M6 数据财务）——只读视图 + 对账人工写。
 *
 * <p>读：资金台账/卡余额/预收池/税务/账户镜像/营收/outbox（类级 finance:view）。
 * 写（B3，DESIGN §5.3）：outbox 人工对账三端点——标记一致 / 标记差异（finance:reconcile）、
 * 差异调平（finance:reconcile:approve，双签复核在前端弹层强制），全部薄调 FundEntryService
 * 状态机（PENDING→RECONCILED/DIFF→ADJUSTED），写接口四件套：校验、幂等（调平分录 idem_key）、
 * 全审计（FUND_RECONCILE / FUND_ADJUST 落 audit_log）、中文错误。资金落账的系统写端点隔离在
 * {@link InternalFundWriteController}（internal:fund-write，仅 X-Internal-Token 可达）。
 *
 * <p>恒等式（DB CHECK 约束保证）：
 *   ① 预收池 total = pending_consume + refundable + earned_pending（940万=658+188+94）
 *   ② revenue_monthly：revenue = cost + gross_profit，cost_rate + gross_rate ≈ 1
 */
@RestController
@RequestMapping("/api/finance")
@RequirePerm("finance:view")
public class FinanceController {

    private final PrepayPoolRepository poolRepo;
    private final TaxRepository taxRepo;
    private final AccountMirrorRepository acctRepo;
    private final RevenueMonthlyRepository revRepo;
    private final OutboxRepository outboxRepo;
    private final CostAllocationRepository costRepo;
    private final FinanceAggregationService aggregation;
    private final FundEntryService fundEntryService;

    public FinanceController(PrepayPoolRepository poolRepo, TaxRepository taxRepo,
                             AccountMirrorRepository acctRepo, RevenueMonthlyRepository revRepo,
                             OutboxRepository outboxRepo, CostAllocationRepository costRepo,
                             FinanceAggregationService aggregation,
                             FundEntryService fundEntryService) {
        this.poolRepo = poolRepo;
        this.taxRepo = taxRepo;
        this.acctRepo = acctRepo;
        this.revRepo = revRepo;
        this.outboxRepo = outboxRepo;
        this.costRepo = costRepo;
        this.aggregation = aggregation;
        this.fundEntryService = fundEntryService;
    }

    /**
     * 台账流水（读时聚合，全只读）：订单收款(RF-REVENUE/IN)、退款(RF-REFUND/OUT)、
     * 卡扣次划扣(RF-DEPOSIT/OUT + RF-REVENUE/IN 成对)。金额单位「元」，门店已解析中文名，
     * 聚合层按登录人门店域逐行收敛。可按 storeCode / from / to(yyyy-MM-dd) 过滤。
     */
    @GetMapping("/ledger")
    public List<FinanceViewDTO.LedgerEntry> ledger(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return aggregation.ledger(storeCode, from, to);
    }

    /**
     * 会员卡余额（读时聚合，全只读）：来自客户域会员卡镜像，金额「元」，
     * 卡类型按总次推断（TIMES/STORED），赠送金无数据源投影 0，最近消费回落开卡时间。
     * 含储值/赠送/疗程估值合计。可按 storeCode 过滤；聚合层按登录人门店域逐行收敛。
     */
    @GetMapping("/cards/balance")
    public FinanceViewDTO.CardBalanceBundle cardsBalance(@RequestParam(required = false) String storeCode) {
        return aggregation.cardBalances(storeCode);
    }

    /** 预收沉淀池（940万 = 待核销 658 + 可退 188 + 待结转 94）。 */
    @GetMapping("/prepay-pool")
    public PrepayPool prepayPool() {
        return poolRepo.findById(1).orElse(new PrepayPool());
    }

    /** 税务四税种（增值/城建/教育费附加/地方教育附加）。 */
    @GetMapping("/tax")
    public List<Tax> tax() {
        return taxRepo.findAll();
    }

    /** 税务合计（全口径）。 */
    @GetMapping("/tax/total")
    public Map<String, Object> taxTotal() {
        List<Tax> all = taxRepo.findAll();
        long sum = all.stream().mapToLong(Tax::getAmount).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalAmount", sum);
        m.put("catCount", all.size());
        return m;
    }

    /** 三账户只读镜像（对公活期/支付宝商户/微信商户）。 */
    @GetMapping("/accounts")
    public List<AccountMirror> accounts() {
        return acctRepo.findAll();
    }

    /** 门店月度营收（可按月份/门店过滤；数据域强制注入）。 */
    @GetMapping("/revenue")
    public List<RevenueMonthly> revenue(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String month) {
        Specification<RevenueMonthly> spec = DataScope.storeSpec("storeCode");
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        if (month != null && !month.isBlank()) {
            LocalDate m = LocalDate.parse(month);
            spec = spec.and((root, q, cb) -> cb.equal(root.get("periodMonth"), m));
        }
        return revRepo.findAll(spec,
                Sort.by(Sort.Order.asc("periodMonth"), Sort.Order.asc("storeCode")));
    }

    /**
     * 门店月度成本汇总（B5）：按「门店 × 月份」聚合 cost_allocation 四类成本（Long 分）。
     * MATERIAL 耗材领用 / LOSS 报损（双签终审自动落账）、DEPRECIATION 折旧 / LABOR 人工（期末人工录入）；
     * 可按月份/门店过滤，数据域逐行收敛。
     */
    @GetMapping("/cost")
    public List<Map<String, Object>> cost(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String month) {
        Specification<CostAllocation> spec = DataScope.storeSpec("storeCode");
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        if (month != null && !month.isBlank()) {
            LocalDate m = LocalDate.parse(month).withDayOfMonth(1);
            spec = spec.and((root, q, cb) -> cb.equal(root.get("periodMonth"), m));
        }
        List<CostAllocation> all = costRepo.findAll(spec,
                Sort.by(Sort.Order.asc("periodMonth"), Sort.Order.asc("storeCode")));
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (CostAllocation c : all) {
            String key = c.getPeriodMonth().toString() + "|" + c.getStoreCode();
            Map<String, Object> row = byKey.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("periodMonth", c.getPeriodMonth().toString());
                m.put("storeCode", c.getStoreCode());
                m.put("material", 0L);
                m.put("loss", 0L);
                m.put("depreciation", 0L);
                m.put("labor", 0L);
                m.put("total", 0L);
                return m;
            });
            String field = switch (c.getCostType()) {
                case "MATERIAL" -> "material";
                case "LOSS" -> "loss";
                case "DEPRECIATION" -> "depreciation";
                case "LABOR" -> "labor";
                default -> null;
            };
            if (field != null) {
                row.merge(field, c.getAmount(), (a, b) -> (Long) a + (Long) b);
                row.merge("total", c.getAmount(), (a, b) -> (Long) a + (Long) b);
            }
        }
        return List.copyOf(byKey.values());
    }

    /**
     * 人工成本录入/分摊（B5，DESIGN §8.2）：POST /api/finance/cost-allocation。
     * 期末录入折旧（DEPRECIATION）/人工（LABOR）；耗材/报损由双签终审自动落账，不在此入口。
     * 同事务落 fund_entry（TK 成本科目，OUT/COST/MANUAL）+ cost_allocation + 月报重算，幂等键 COST:单号。
     * body：{"costType":"DEPRECIATION|LABOR","storeCode":"S01","month":"2026-09-01","amountFen":12300,"memo":"..."}。
     */
    @PostMapping("/cost-allocation")
    @RequirePerm("finance:cost:edit")
    public Map<String, Object> costAllocation(@RequestBody Map<String, Object> body) {
        String costType = body.get("costType") == null ? null : String.valueOf(body.get("costType"));
        String sc = body.get("storeCode") == null ? null : String.valueOf(body.get("storeCode"));
        String monthStr = body.get("month") == null ? null : String.valueOf(body.get("month"));
        Long amountFen = body.get("amountFen") == null ? null
                : Long.valueOf(String.valueOf(body.get("amountFen")));
        String memo = body.get("memo") == null ? null : String.valueOf(body.get("memo"));
        LocalDate month = monthStr == null || monthStr.isBlank() ? null : LocalDate.parse(monthStr);
        return fundEntryService.recordManualCost(costType, sc, month, amountFen, memo,
                SecurityContext.currentStaffId());
    }

    /** Outbox 对账台账（可按状态过滤：PENDING 待对账 / RECONCILED 已对账 / DIFF 差异 / ADJUSTED 已调平）。 */
    @GetMapping("/outbox")
    public List<OutboxRecord> outbox(@RequestParam(required = false) String status) {
        return status == null ? outboxRepo.findAll() : outboxRepo.findByStatusOrderByCreatedAtDesc(status);
    }

    /** 对账核验（只读）：统计各状态笔数、净额（分）与差异笔数（DIFF 待调平 + ADJUSTED 已调平）。 */
    @GetMapping("/outbox/reconcile")
    public Map<String, Object> reconcile() {
        List<OutboxRecord> all = outboxRepo.findAll();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long net = 0;
        long diffRecords = 0;
        for (OutboxRecord r : all) {
            byStatus.merge(r.getStatus(), 1L, Long::sum);
            net += r.getAmount();
            if ("DIFF".equals(r.getStatus()) || "ADJUSTED".equals(r.getStatus())) diffRecords++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRecords", all.size());
        m.put("byStatus", byStatus);
        m.put("netAmount", net);
        m.put("diffRecords", diffRecords);
        return m;
    }

    /**
     * 人工对账 · 标记一致：POST /api/finance/outbox/{id}/mark-reconciled。
     * PENDING → RECONCILED；body 可带 remark（入审计 payload）。财务/超管（finance:reconcile）。
     */
    @PostMapping("/outbox/{id}/mark-reconciled")
    @RequirePerm("finance:reconcile")
    public Map<String, Object> markReconciled(@PathVariable("id") Long id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        String remark = body != null && body.get("remark") != null ? String.valueOf(body.get("remark")) : null;
        return fundEntryService.reconcileOutbox(id, false, remark, SecurityContext.currentStaffId());
    }

    /**
     * 人工对账 · 标记差异：POST /api/finance/outbox/{id}/mark-diff。
     * PENDING → DIFF（待调平）；body 可带 remark。财务/超管（finance:reconcile）。
     */
    @PostMapping("/outbox/{id}/mark-diff")
    @RequirePerm("finance:reconcile")
    public Map<String, Object> markDiff(@PathVariable("id") Long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        String remark = body != null && body.get("remark") != null ? String.valueOf(body.get("remark")) : null;
        return fundEntryService.reconcileOutbox(id, true, remark, SecurityContext.currentStaffId());
    }

    /**
     * 差异调平：POST /api/finance/outbox/{id}/adjust。
     * DIFF → ADJUSTED，补一条 ADJUST 调整分录（幂等）。财务主管/超管（finance:reconcile:approve），
     * 前端弹层强制双签（操作人 + 复核人）。body：direction/amountFen/subject/channel/memo。
     */
    @PostMapping("/outbox/{id}/adjust")
    @RequirePerm("finance:reconcile:approve")
    public Map<String, Object> adjust(@PathVariable("id") Long id,
                                      @RequestBody Map<String, Object> body) {
        String direction = body.get("direction") == null ? null : String.valueOf(body.get("direction"));
        Long amountFen = body.get("amountFen") == null ? null
                : Long.valueOf(String.valueOf(body.get("amountFen")));
        String subject = body.get("subject") == null ? null : String.valueOf(body.get("subject"));
        String channel = body.get("channel") == null ? null : String.valueOf(body.get("channel"));
        String memo = body.get("memo") == null ? null : String.valueOf(body.get("memo"));
        return fundEntryService.adjustOutbox(id, direction, amountFen, subject, channel, memo,
                SecurityContext.currentStaffId());
    }
}
