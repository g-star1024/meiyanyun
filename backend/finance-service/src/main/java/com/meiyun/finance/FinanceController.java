package com.meiyun.finance;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    private final TripartiteReconcileService tripartiteService;
    private final SettlementService settlementService;
    private final FinanceExportService exportService;

    public FinanceController(PrepayPoolRepository poolRepo, TaxRepository taxRepo,
                             AccountMirrorRepository acctRepo, RevenueMonthlyRepository revRepo,
                             OutboxRepository outboxRepo, CostAllocationRepository costRepo,
                             FinanceAggregationService aggregation,
                             FundEntryService fundEntryService,
                             TripartiteReconcileService tripartiteService,
                             SettlementService settlementService,
                             FinanceExportService exportService) {
        this.poolRepo = poolRepo;
        this.taxRepo = taxRepo;
        this.acctRepo = acctRepo;
        this.revRepo = revRepo;
        this.outboxRepo = outboxRepo;
        this.costRepo = costRepo;
        this.aggregation = aggregation;
        this.fundEntryService = fundEntryService;
        this.tripartiteService = tripartiteService;
        this.settlementService = settlementService;
        this.exportService = exportService;
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

    /**
     * 单卡余额变动时间线（B24 卡2）：GET /api/finance/cards/{cardNo}/timeline。
     * 卡快照 + card_ledger 全量流水（充值/划扣/退款/调整），金额「元」、时间上海时区可读串。
     * 卡不存在或登录人无该卡门店域权限统一 404（不泄露卡号存在性）；空卡号 400 中文。
     */
    @GetMapping("/cards/{cardNo}/timeline")
    public FinanceViewDTO.CardTimeline cardTimeline(@PathVariable("cardNo") String cardNo) {
        FinanceViewDTO.CardTimeline timeline = aggregation.cardTimeline(cardNo);
        if (timeline == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "卡不存在或无权查看该卡：" + cardNo);
        }
        return timeline;
    }

    /**
     * 核销双签明细（B24 卡2）：GET /api/finance/writeoff-details。
     * 全状态核销（DONE/ABNORMAL/VOID）+ 操作人/双签留痕，金额「元」，店名/客户名已解析，
     * 聚合层按登录人门店域逐行收敛。参数均可选：storeCode/status/cardNo/customerId/keyword/from/to。
     */
    @GetMapping("/writeoff-details")
    public List<FinanceViewDTO.WriteoffDetail> writeoffDetails(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cardNo,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return aggregation.writeoffDetails(storeCode, status, cardNo, customerId, keyword, from, to);
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
     * 三方对账（B7，DESIGN §9.1）：GET /api/finance/reconcile/tripartite?date=2026-09-06&storeCode=SST01。
     * 按日聚合 经营域（txn 订单收款/退款/划扣/退卡）× 资金域（fund_entry 落账净额）× 现金日结
     * （「现金交接」双签工单实点现金），输出各方净额（分/元双金额）、门店明细与差异笔数。
     * 账账差异扣除财务域独有的期末成本录入（MANUAL）后计；ADJUST 人工调平单列；
     * 微信/支付宝/银行回单本期未接入，账实核对仅覆盖现金渠道；现金方拉取失败时诚实降级仅出账账结果。
     * 本端点只读（类级 finance:view），聚合层按登录人门店域逐行收敛，差异处理走 outbox DIFF/ADJUST。
     */
    @GetMapping("/reconcile/tripartite")
    public Map<String, Object> tripartite(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String storeCode) {
        return tripartiteService.tripartite(date, storeCode);
    }

    /**
     * 封账台账（B7，DESIGN §9.2）：GET /api/finance/settlement?periodType=DAY&periodKey=2026-09-05&storeCode=SST01。
     * 返回已封账期间列表（日结 DAY / 月结 MONTH × 门店，含封账时点分录笔数与净额快照）。
     * 财务/超管（finance:settlement:view）；数据域逐行收敛，无权门店不返回。
     */
    @GetMapping("/settlement")
    @RequirePerm("finance:settlement:view")
    public List<Map<String, Object>> settlement(
            @RequestParam(required = false) String periodType,
            @RequestParam(required = false) String periodKey,
            @RequestParam(required = false) String storeCode) {
        return settlementService.list(periodType, periodKey, storeCode);
    }

    /**
     * 日结/月结封账（B7，DESIGN §9.2）：POST /api/finance/settlement。
     * 锁定该「期间 × 门店」：封账后任何 occurredAt 落入该期间的新分录在落账环节 422 拒绝，
     * 差错只能走 ADJUST 调平（落当前期间）。封账永久、无解封；重复封账幂等重放。
     * 财务主管/超管（finance:settlement:edit）；全审计（FIN_SETTLE）。
     * body：{"periodType":"DAY|MONTH","periodKey":"2026-09-05|2026-09","storeCode":"SST01","memo":"..."}。
     */
    @PostMapping("/settlement")
    @RequirePerm("finance:settlement:edit")
    public Map<String, Object> settle(@RequestBody Map<String, Object> body) {
        String periodType = body.get("periodType") == null ? null : String.valueOf(body.get("periodType"));
        String periodKey = body.get("periodKey") == null ? null : String.valueOf(body.get("periodKey"));
        String storeCode = body.get("storeCode") == null ? null : String.valueOf(body.get("storeCode"));
        String memo = body.get("memo") == null ? null : String.valueOf(body.get("memo"));
        return settlementService.close(periodType, periodKey, storeCode, memo,
                SecurityContext.currentStaffId());
    }

    /**
     * 封账台账导出 CSV（B8）：GET /api/finance/export/settlement.csv，过滤参数同 GET /settlement。
     * 数据薄调 {@link SettlementService#list}，权限与数据域与页面同源（finance:settlement:view）。
     * UTF-8 BOM + 中文表头 + 金额「元」，Excel 可直接打开。
     */
    @GetMapping("/export/settlement.csv")
    @RequirePerm("finance:settlement:view")
    public ResponseEntity<byte[]> exportSettlement(
            @RequestParam(required = false) String periodType,
            @RequestParam(required = false) String periodKey,
            @RequestParam(required = false) String storeCode) {
        return csvResponse(exportService.exportSettlement(periodType, periodKey, storeCode));
    }

    /** 三方对账门店明细导出 CSV（B8）：GET /api/finance/export/tripartite.csv，参数同 GET /reconcile/tripartite。 */
    @GetMapping("/export/tripartite.csv")
    public ResponseEntity<byte[]> exportTripartite(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String storeCode) {
        return csvResponse(exportService.exportTripartite(date, storeCode));
    }

    /** 资金分录台账导出 CSV（B8）：GET /api/finance/export/ledger.csv，参数同 GET /ledger。 */
    @GetMapping("/export/ledger.csv")
    public ResponseEntity<byte[]> exportLedger(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return csvResponse(exportService.exportLedger(storeCode, from, to));
    }

    /** 四类成本汇总导出 CSV（B8）：GET /api/finance/export/cost.csv，参数同 GET /cost。 */
    @GetMapping("/export/cost.csv")
    public ResponseEntity<byte[]> exportCost(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String month) {
        return csvResponse(exportService.exportCost(storeCode, month));
    }

    /**
     * 发票台账导出 CSV（B24 卡1）：GET /api/finance/export/invoices.csv，
     * 过滤参数与权限（finance:invoice:view）同 FinConfigController#listInvoices。
     */
    @GetMapping("/export/invoices.csv")
    @RequirePerm("finance:invoice:view")
    public ResponseEntity<byte[]> exportInvoices(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword) {
        return csvResponse(exportService.exportInvoices(storeCode, status, type, keyword));
    }

    /**
     * 单卡流水时间线导出 CSV（B24 卡2）：GET /api/finance/export/card-ledger.csv?cardNo=。
     * 数据与权限同源 GET /cards/{cardNo}/timeline（卡不存在/越权同样 404 中文）。
     */
    @GetMapping("/export/card-ledger.csv")
    public ResponseEntity<byte[]> exportCardLedger(@RequestParam("cardNo") String cardNo) {
        FinanceViewDTO.CardTimeline timeline = aggregation.cardTimeline(cardNo);
        if (timeline == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "卡不存在或无权查看该卡：" + cardNo);
        }
        return csvResponse(exportService.exportCardTimeline(timeline));
    }

    /**
     * 核销双签明细导出 CSV（B24 卡2）：GET /api/finance/export/writeoffs.csv，参数同 GET /writeoff-details。
     */
    @GetMapping("/export/writeoffs.csv")
    public ResponseEntity<byte[]> exportWriteoffs(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cardNo,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return csvResponse(exportService.exportWriteoffDetails(
                aggregation.writeoffDetails(storeCode, status, cardNo, customerId, keyword, from, to)));
    }

    /** 统一 CSV 附件响应：Content-Disposition 中文文件名走 filename*=UTF-8'' 编码，兼容 BOM 防乱码。 */
    private ResponseEntity<byte[]> csvResponse(FinanceExportService.CsvReport report) {
        String encoded = URLEncoder.encode(report.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"export.csv\"; filename*=UTF-8''" + encoded)
                .contentLength(report.content().length)
                .body(report.content());
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
