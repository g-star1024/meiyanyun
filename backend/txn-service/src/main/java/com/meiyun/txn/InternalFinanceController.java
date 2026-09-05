package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import com.meiyun.txn.audit.AuditRecorder;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * finance-service 聚合数据源（服务间内部端点，非业务页面）。
 *
 * <p>红线边界：本端点仅向 finance-service 以系统身份（X-Internal-Token）开放只读投影，
 * 普通登录人无 {@code internal:finance-flow} 权限 → 403。财务聚合不直接 JOIN/直读交易表，
 * 由交易域按自身实体语义提供资金流水，微服务按域拆库后零改动成立。
 *
 * <p>过滤：storeCode / from / to（yyyy-MM-dd，按 created_at 闭区间）均可选；内部身份为 GROUP 域，
 * 数据权限收敛由 finance-service 调用后按登录人门店域二次过滤（边界服务已强制自身 DataScope）。
 *
 * <p>B6 G1 双账核对/回填（划扣两账一致性，customer 卡台账为权威）：
 * <ul>
 *   <li>GET  /writeoff-reconcile：干跑——扫 txn 侧 DONE 划扣，批量拉 customer CONSUME 流水比对，
 *       输出缺流水 / 卡不匹配 / 金额不符三类差异，不做任何写入；customer 不可用即 502，不出「全部一致」假结果。</li>
 *   <li>POST /writeoff-backfill：对「缺流水」差异逐笔重放 backfill=true（只补 card_ledger 流水、
 *       不重复扣卡——共表部署下 member_card 次数/余额已于历史划扣时扣减）；WO 单号幂等防双写，
 *       4xx（卡 404 / WO 冲突 409 等）收集为 skipped-diff 不中断，5xx/网络异常即整体中止（已补笔数
 *       幂等可重放）；回填前后双账快照 + 全审计。卡不匹配 / 金额不符属账实差异，禁止自动改数，只挂人工调平。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/txn/internal")
public class InternalFinanceController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter RUN_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TxnOrderRepository orderRepo;
    private final TxnRefundRepository refundRepo;
    private final WriteoffRepository writeoffRepo;
    private final TxnCardCancelRepository cardCancelRepo;
    private final PaymentService paymentService;
    private final CustomerCardClient customerCardClient;
    private final AuditRecorder audit;

    public InternalFinanceController(TxnOrderRepository orderRepo, TxnRefundRepository refundRepo,
                                     WriteoffRepository writeoffRepo, TxnCardCancelRepository cardCancelRepo,
                                     PaymentService paymentService, CustomerCardClient customerCardClient,
                                     AuditRecorder audit) {
        this.orderRepo = orderRepo;
        this.refundRepo = refundRepo;
        this.writeoffRepo = writeoffRepo;
        this.cardCancelRepo = cardCancelRepo;
        this.paymentService = paymentService;
        this.customerCardClient = customerCardClient;
        this.audit = audit;
    }

    /**
     * 资金流水批量投影：GET /api/txn/internal/finance-flows?storeCode=SST01&from=2026-08-01&to=2026-08-31。
     * 返回已收款订单 / 已退款流水 / 已划扣核销 / 已退卡四类，供 finance 台账读时聚合。
     */
    @GetMapping("/finance-flows")
    @RequirePerm("internal:finance-flow")
    public FinanceFlowDTO.Bundle financeFlows(
            @RequestParam(value = "storeCode", required = false) String storeCode,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {

        OffsetDateTime fromTime = from == null || from.isBlank() ? null
                : LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime toTime = to == null || to.isBlank() ? null
                : LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

        List<TxnOrder> paidOrders = orderRepo.findAll(orderSpec(storeCode, fromTime, toTime));
        // 批量取区间内已收款订单的支付流水（防 N+1），按 orderNo 归组算渠道口径
        java.util.Map<String, List<OrderPayment>> paysByOrder =
                paymentService.loadByOrders(paidOrders.stream().map(TxnOrder::getOrderNo).toList());
        List<FinanceFlowDTO.OrderFlow> orders = paidOrders.stream()
                .map(o -> {
                    PayChannel pc = resolvePayChannel(paysByOrder.get(o.getOrderNo()));
                    return new FinanceFlowDTO.OrderFlow(o.getOrderNo(), o.getStoreCode(), o.getCustomerId(),
                            o.getProject(), o.getAmount(), o.getStatus(), o.getCreatedAt(), pc.method(), pc.mixed());
                })
                .toList();

        List<FinanceFlowDTO.RefundFlow> refunds = refundRepo.findAll(refundSpec(storeCode, fromTime, toTime)).stream()
                .map(r -> new FinanceFlowDTO.RefundFlow(r.getTxnNo(), r.getOrderNo(), r.getStoreCode(),
                        r.getCustomerName(), r.getChannel(), r.getRefundAmt(), r.getFee(), r.getStatus(),
                        r.getCreatedAt()))
                .toList();

        List<FinanceFlowDTO.WriteoffFlow> writeoffs = writeoffRepo.findAll(writeoffSpec(storeCode, fromTime, toTime)).stream()
                .map(w -> new FinanceFlowDTO.WriteoffFlow(w.getWriteoffId(), w.getOrderNo(), w.getCardNo(),
                        w.getStoreCode(), w.getProject(), w.getTimesUsed(), w.getAmount(), w.getStatus(),
                        w.getCreatedAt()))
                .toList();

        List<FinanceFlowDTO.CardCancelFlow> cardCancels = cardCancelRepo.findAll(cardCancelSpec(storeCode, fromTime, toTime)).stream()
                .map(c -> new FinanceFlowDTO.CardCancelFlow(c.getTxnNo(), c.getCardNo(), c.getStoreCode(),
                        c.getCustomerName(), c.getChannel(), c.getBalance(), c.getRefundAmt(), c.getFee(),
                        c.getStatus(), c.getCreatedAt()))
                .toList();

        return new FinanceFlowDTO.Bundle(orders, refunds, writeoffs, cardCancels);
    }

    /**
     * 划扣双账核对（B6 G1，干跑）：GET /api/txn/internal/writeoff-reconcile?storeCode=&from=&to=。
     * 扫 txn 侧 status=DONE 且 card_no 非空的划扣，批量拉 customer card_ledger 中以 WO 单号为 bizRef
     * 的 CONSUME 流水逐笔比对（customer 为权威卡台账），返回一致总数与三类差异——
     * MISSING_LEDGER（customer 无该 WO 流水，B6 前存量划扣的典型缺口）/
     * CARD_MISMATCH（流水落在别的卡上）/ AMOUNT_MISMATCH（流水额 ≠ 划扣额，ledger.amount 应 = -amount）。
     * 本端点只读不写；customer 不可用直接 502 中止（CustomerCardClient 已兜底），绝不出「全部一致」假结果。
     */
    @GetMapping("/writeoff-reconcile")
    @RequirePerm("internal:finance-flow")
    public ReconcileResult writeoffReconcile(
            @RequestParam(value = "storeCode", required = false) String storeCode,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        OffsetDateTime fromTime = from == null || from.isBlank() ? null
                : LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime toTime = to == null || to.isBlank() ? null
                : LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        return reconcile(writeoffSpec(storeCode, fromTime, toTime));
    }

    /**
     * 存量划扣回填（B6 G1，一次性运维端点）：POST /api/txn/internal/writeoff-backfill。
     * 请求体 {@code {"storeCode":"SST01","from":"2026-01-01","to":"2026-09-05"}} 均可选（缺省全量）。
     * 流程：先干跑核对取回填前快照 → 仅对 MISSING_LEDGER 差异逐笔调 customer backfill=true
     * （只补 CONSUME 流水，不扣 remain_times/balance）→ 再核对一次取回填后快照。
     * WO 单号幂等：重放不双写；customer 4xx（卡不存在 404 / WO 冲突 409 等）逐笔收集为 skipped-diff
     * 不中断整批；5xx/网络异常立即中止（已补笔数凭 WO 幂等可安全重跑）。卡不匹配/金额不符不自动回填，
     * 随 before/after 差异清单返回，由人工调平。全程 WRITEOFF_RECONCILE/WRITEOFF_BACKFILL_RUN 审计。
     */
    @PostMapping("/writeoff-backfill")
    @RequirePerm("internal:finance-flow")
    public BackfillResult writeoffBackfill(@RequestBody(required = false) BackfillCmd cmd) {
        String storeCode = cmd == null ? null : cmd.storeCode();
        OffsetDateTime fromTime = cmd == null || cmd.from() == null || cmd.from().isBlank() ? null
                : LocalDate.parse(cmd.from()).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime toTime = cmd == null || cmd.to() == null || cmd.to().isBlank() ? null
                : LocalDate.parse(cmd.to()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        Specification<WriteoffRecord> spec = writeoffSpec(storeCode, fromTime, toTime);
        String actor = DataScope.currentActor();
        String runId = "WBR" + LocalDateTime.now(ZoneOffset.UTC).format(RUN_TS);

        ReconcileResult before = reconcile(spec);
        audit.record("WRITEOFF", runId, actor, "WRITEOFF_RECONCILE",
                json(Map.of("runId", runId, "phase", "before-backfill",
                        "totalDone", before.totalDone(), "okCount", before.okCount(),
                        "missing", before.missingLedger().size(),
                        "cardMismatch", before.cardMismatch().size(),
                        "amountMismatch", before.amountMismatch().size())));

        List<BackfilledItem> backfilled = new ArrayList<>();
        List<SkippedDiff> skipped = new ArrayList<>();
        for (WriteoffDiff d : before.missingLedger()) {
            try {
                customerCardClient.writeoff(d.cardNo(), d.writeoffId(),
                        d.timesUsed() == null ? 1 : d.timesUsed(),
                        d.amount() == null ? 0L : d.amount(), d.storeCode(), true);
                backfilled.add(new BackfilledItem(d.writeoffId(), d.cardNo(), d.storeCode(),
                        d.timesUsed(), d.amount(), "BACKFILLED"));
            } catch (ResponseStatusException e) {
                int sc = e.getStatusCode().value();
                if (sc >= 500 || sc == HttpStatus.BAD_GATEWAY.value()) {
                    // 客户服务不可用：资金安全红线，立即中止（已补笔数 WO 幂等，重跑安全）
                    audit.record("WRITEOFF", runId, actor, "WRITEOFF_BACKFILL_RUN",
                            json(runPayload(runId, "ABORTED", before, backfilled, skipped,
                                    "回填中止：客户服务暂不可用（" + sc + "），已补 " + backfilled.size()
                                            + " 笔凭 WO 幂等可安全重跑")));
                    throw e;
                }
                // 4xx：卡不存在 / WO 冲突等——逐笔收集为差异，交人工调平，不中断整批
                skipped.add(new SkippedDiff(d.writeoffId(), d.cardNo(), sc,
                        e.getReason() == null ? "客户服务拒绝回填" : e.getReason()));
            }
        }

        ReconcileResult after = reconcile(spec);
        String warning = after.diffCount() == 0 ? null
                : "仍有 " + after.diffCount() + " 笔双账差异（卡不匹配/金额不符/回填被拒），禁止自动改数，请人工调平";
        audit.record("WRITEOFF", runId, actor, "WRITEOFF_BACKFILL_RUN",
                json(runPayload(runId, "DONE", before, backfilled, skipped, warning)));
        return new BackfillResult(runId, before, after, List.copyOf(backfilled), List.copyOf(skipped), warning);
    }

    /** 双账核对核心：txn DONE 划扣 × customer WO 流水比对，只读。 */
    private ReconcileResult reconcile(Specification<WriteoffRecord> spec) {
        List<WriteoffRecord> done = writeoffRepo.findAll(spec).stream()
                .filter(w -> w.getCardNo() != null && !w.getCardNo().isBlank())
                .toList();
        List<Map<String, Object>> ledgers = customerCardClient.writeoffLedgers(
                done.stream().map(WriteoffRecord::getWriteoffId).toList());
        Map<String, Map<String, Object>> byRef = new LinkedHashMap<>();
        for (Map<String, Object> l : ledgers) {
            Object ref = l.get("bizRef");
            if (ref != null) byRef.putIfAbsent(ref.toString(), l);
        }
        List<WriteoffDiff> missing = new ArrayList<>();
        List<WriteoffDiff> cardMismatch = new ArrayList<>();
        List<WriteoffDiff> amountMismatch = new ArrayList<>();
        for (WriteoffRecord w : done) {
            Map<String, Object> l = byRef.get(w.getWriteoffId());
            long amount = w.getAmount() == null ? 0L : w.getAmount();
            int times = w.getTimesUsed() == null ? 1 : w.getTimesUsed();
            if (l == null) {
                missing.add(new WriteoffDiff(w.getWriteoffId(), w.getCardNo(), w.getStoreCode(),
                        times, amount, null, null, "MISSING_LEDGER"));
                continue;
            }
            Object ledgerCard = l.get("cardNo");
            if (ledgerCard != null && !w.getCardNo().equals(ledgerCard.toString())) {
                cardMismatch.add(new WriteoffDiff(w.getWriteoffId(), w.getCardNo(), w.getStoreCode(),
                        times, amount, ledgerCard.toString(), num(l.get("amount")), "CARD_MISMATCH"));
                continue;
            }
            Long ledgerAmount = num(l.get("amount"));
            // customer CONSUME 流水为负额，应等于 -划扣额
            if (ledgerAmount == null || ledgerAmount != -amount) {
                amountMismatch.add(new WriteoffDiff(w.getWriteoffId(), w.getCardNo(), w.getStoreCode(),
                        times, amount, ledgerCard == null ? null : ledgerCard.toString(),
                        ledgerAmount, "AMOUNT_MISMATCH"));
            }
        }
        int diffCount = missing.size() + cardMismatch.size() + amountMismatch.size();
        return new ReconcileResult(done.size(), done.size() - diffCount, diffCount,
                List.copyOf(missing), List.copyOf(cardMismatch), List.copyOf(amountMismatch));
    }

    private Map<String, Object> runPayload(String runId, String phase, ReconcileResult before,
                                           List<BackfilledItem> backfilled, List<SkippedDiff> skipped,
                                           String warning) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("runId", runId);
        p.put("phase", phase);
        p.put("beforeTotalDone", before.totalDone());
        p.put("beforeMissing", before.missingLedger().size());
        p.put("backfilled", backfilled.size());
        p.put("skipped", skipped.size());
        p.put("warning", warning);
        return p;
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return null;
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 回填请求体（字段均可选，缺省全量扫描）。 */
    public record BackfillCmd(String storeCode, String from, String to) {}

    /** 核对结果：totalDone=txn DONE 划扣总数（card_no 非空），okCount=两账一致数，其余为三类差异清单。 */
    public record ReconcileResult(int totalDone, int okCount, int diffCount,
                                  List<WriteoffDiff> missingLedger, List<WriteoffDiff> cardMismatch,
                                  List<WriteoffDiff> amountMismatch) {}

    /** 单笔差异：type ∈ MISSING_LEDGER / CARD_MISMATCH / AMOUNT_MISMATCH；ledgerCardNo/ledgerAmount 为 customer 侧实况。 */
    public record WriteoffDiff(String writeoffId, String cardNo, String storeCode, Integer timesUsed,
                               Long amount, String ledgerCardNo, Long ledgerAmount, String type) {}

    /** 回填结果：before/after 双快照 + 已补清单 + 跳过差异 + 人工调平提示。 */
    public record BackfillResult(String runId, ReconcileResult before, ReconcileResult after,
                                 List<BackfilledItem> backfilled, List<SkippedDiff> skipped, String warning) {}

    public record BackfilledItem(String writeoffId, String cardNo, String storeCode,
                                 Integer timesUsed, Long amount, String result) {}

    public record SkippedDiff(String writeoffId, String cardNo, int httpStatus, String reason) {}

    private Specification<TxnOrder> orderSpec(String storeCode, OffsetDateTime from, OffsetDateTime to) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("status"), "已收款"));
            if (storeCode != null && !storeCode.isBlank()) ps.add(cb.equal(root.get("storeCode"), storeCode));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Specification<TxnRefund> refundSpec(String storeCode, OffsetDateTime from, OffsetDateTime to) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("status"), "REFUNDED"));
            if (storeCode != null && !storeCode.isBlank()) ps.add(cb.equal(root.get("storeCode"), storeCode));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Specification<WriteoffRecord> writeoffSpec(String storeCode, OffsetDateTime from, OffsetDateTime to) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("status"), "DONE"));
            if (storeCode != null && !storeCode.isBlank()) ps.add(cb.equal(root.get("storeCode"), storeCode));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Specification<TxnCardCancel> cardCancelSpec(String storeCode, OffsetDateTime from, OffsetDateTime to) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("status"), "REFUNDED"));
            if (storeCode != null && !storeCode.isBlank()) ps.add(cb.equal(root.get("storeCode"), storeCode));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** 订单渠道口径解析结果：method 为主渠道码（入账额最大一笔），mixed 为一单多渠道标记。 */
    private record PayChannel(String method, boolean mixed) {}

    /**
     * 混合支付口径（设计 §4.1 方案 a）：一单多笔收款时，渠道取 postedAmount（入账额）最大一笔的
     * payMethod；不同渠道数 &gt; 1 置 mixed=true（前端展示「混合（主：xx）」）。
     * 无支付流水（历史/异常数据）→ method=null、mixed=false，由 finance 侧保守留空。
     */
    private PayChannel resolvePayChannel(List<OrderPayment> pays) {
        if (pays == null || pays.isEmpty()) return new PayChannel(null, false);
        String main = null;
        long maxPosted = Long.MIN_VALUE;
        for (OrderPayment p : pays) {
            long posted = p.getPostedAmount() == null ? 0L : p.getPostedAmount();
            // 同额时以 paymentId 升序先出现者为准（findByOrderNoInOrderByPaymentIdAsc 已排序，结果确定）
            if (posted > maxPosted) {
                maxPosted = posted;
                main = p.getPayMethod();
            }
        }
        boolean mixed = pays.stream().map(OrderPayment::getPayMethod).distinct().count() > 1;
        return new PayChannel(main, mixed);
    }
}
