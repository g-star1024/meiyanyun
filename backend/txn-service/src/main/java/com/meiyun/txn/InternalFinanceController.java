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
    private final DualSignTicketRepository ticketRepo;
    private final AuditRecorder audit;

    public InternalFinanceController(TxnOrderRepository orderRepo, TxnRefundRepository refundRepo,
                                     WriteoffRepository writeoffRepo, TxnCardCancelRepository cardCancelRepo,
                                     PaymentService paymentService, CustomerCardClient customerCardClient,
                                     DualSignTicketRepository ticketRepo, AuditRecorder audit) {
        this.orderRepo = orderRepo;
        this.refundRepo = refundRepo;
        this.writeoffRepo = writeoffRepo;
        this.cardCancelRepo = cardCancelRepo;
        this.paymentService = paymentService;
        this.customerCardClient = customerCardClient;
        this.ticketRepo = ticketRepo;
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
                        w.getCreatedAt(), w.getCustomerId(), w.getOperator(), w.getSign1(), w.getSign2(),
                        w.getAbnormalReason()))
                .toList();

        List<FinanceFlowDTO.CardCancelFlow> cardCancels = cardCancelRepo.findAll(cardCancelSpec(storeCode, fromTime, toTime)).stream()
                .map(c -> new FinanceFlowDTO.CardCancelFlow(c.getTxnNo(), c.getCardNo(), c.getStoreCode(),
                        c.getCustomerName(), c.getChannel(), c.getBalance(), c.getRefundAmt(), c.getFee(),
                        c.getStatus(), c.getCreatedAt()))
                .toList();

        return new FinanceFlowDTO.Bundle(orders, refunds, writeoffs, cardCancels);
    }

    /**
     * 核销双签明细投影（B24 卡2，财务核销明细页取数）：
     * GET /api/txn/internal/writeoff-details?storeCode=&status=&cardNo=&customerId=&keyword=&from=&to=。
     *
     * <p>与 {@link #financeFlows} 中固定 status='DONE' 的台账聚合口径不同，本端点不固化状态，
     * 供财务核销明细页展示全状态核销单（DONE 已核销 / ABNORMAL 异常 / VOID 已作废）及双签留痕：
     * operator 操作人工号、sign1 操作人、sign2 复核人（「工号 姓名」，整单核销/历史单可空）、
     * timesUsed 扣次次数、abnormalReason 异常/作废原因。
     *
     * <p>过滤均可选：status 精确（非法值 400 中文）、storeCode 精确、cardNo 精确（仅疗程卡扣次路径）、
     * customerId 精确、keyword 模糊匹配核销号/订单号/卡号/项目名；from/to 按 created_at 半开区间（UTC 日界）。
     * 结果按 created_at 倒序。不做 DataScope 收敛——由 finance-service 按登录人门店域二次过滤（与 finance-flows 同边界）。
     */
    @GetMapping("/writeoff-details")
    @RequirePerm("internal:finance-flow")
    public List<FinanceFlowDTO.WriteoffFlow> writeoffDetails(
            @RequestParam(value = "storeCode", required = false) String storeCode,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "cardNo", required = false) String cardNo,
            @RequestParam(value = "customerId", required = false) String customerId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        String st = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        if (st != null && !st.equals("DONE") && !st.equals("ABNORMAL") && !st.equals("VOID")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "核销状态参数 status 非法，仅支持 DONE（已核销）/ABNORMAL（异常）/VOID（已作废）：" + status);
        }
        OffsetDateTime fromTime = from == null || from.isBlank() ? null
                : LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime toTime = to == null || to.isBlank() ? null
                : LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        Specification<WriteoffRecord> spec = writeoffDetailSpec(
                blankToNull(storeCode), st, blankToNull(cardNo), blankToNull(customerId),
                blankToNull(keyword), fromTime, toTime);
        return writeoffRepo.findAll(spec, org.springframework.data.domain.Sort
                        .by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")).stream()
                .map(w -> new FinanceFlowDTO.WriteoffFlow(w.getWriteoffId(), w.getOrderNo(), w.getCardNo(),
                        w.getStoreCode(), w.getProject(), w.getTimesUsed(), w.getAmount(), w.getStatus(),
                        w.getCreatedAt(), w.getCustomerId(), w.getOperator(), w.getSign1(), w.getSign2(),
                        w.getAbnormalReason()))
                .toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 现金日结内部投影（B7 三方对账）：GET /api/txn/internal/cash-settle?date=2026-09-05&storeCode=SST01。
     *
     * <p>与面向页面的 {@link M4RepurchaseController#cashSettle}（daily:view + 登录人门店域收敛）不同，
     * 本端点供 finance-service 以系统身份跨域取数：不做 DataScope 门店域收敛（收敛由 finance 侧
     * 按登录人域二次过滤，与 finance-flows 同边界），按「现金交接」类已完成双签工单聚合，
     * 日界取 Asia/Shanghai 自然日（双签完成时刻 signed_at2）。金额单位 Long「分」。
     *
     * @return 按门店明细行 + 合计（totalAmount 分 / totalAmountYuan 元 / ticketCount 张数）
     */
    @GetMapping("/cash-settle")
    @RequirePerm("internal:finance-flow")
    public Map<String, Object> cashSettleInternal(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "storeCode", required = false) String storeCode) {
        String day = (date == null || date.isBlank())
                ? java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString() : date.trim();
        try {
            java.time.LocalDate.parse(day);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "日期参数 date 格式非法，需 yyyy-MM-dd（如 2026-09-05）：" + day);
        }
        List<DualSignTicketRepository.CashSettleRow> rows = ticketRepo.cashSettleByDate(day,
                (storeCode == null || storeCode.isBlank()) ? null : storeCode.trim());
        List<Map<String, Object>> details = rows.stream().<Map<String, Object>>map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("storeCode", r.getStoreCode());
            row.put("totalAmount", r.getTotalAmount() == null ? 0L : r.getTotalAmount());
            row.put("ticketCount", r.getTicketCount() == null ? 0L : r.getTicketCount());
            return row;
        }).toList();
        long totalAmount = details.stream().mapToLong(d -> (Long) d.get("totalAmount")).sum();
        long ticketCount = details.stream().mapToLong(d -> (Long) d.get("ticketCount")).sum();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("date", day);
        r.put("store", (storeCode == null || storeCode.isBlank()) ? "ALL" : storeCode.trim());
        r.put("currency", "CNY");
        r.put("unit", "分");
        r.put("details", details);
        r.put("totalAmount", totalAmount);
        r.put("totalAmountYuan", totalAmount / 100.0);
        r.put("ticketCount", ticketCount);
        return r;
    }

    /**
     * 提成业绩基数聚合（B9，设计 §5.3）：GET /api/txn/internal/commission-base?month=2026-09&storeCode=SST01。
     *
     * <p>供 finance-service 提成试算跨域取数（X-Internal-Token，internal:finance-flow）：按自然月（UTC 月界，
     * 与 finance-flows 日界口径一致）、按订单顾问工号（txn_order.consultant）聚合三类分量，finance 侧按
     * 提成规则 base 口径自取对应分量：
     * <ul>
     *   <li>writeoff*：writeoff_record(status=DONE) 经 order_no JOIN txn_order 取顾问，汇总划扣额/笔数
     *       （WriteoffRecord 无 consultant 字段）——WRITEOFF 划扣确认收入口径；</li>
     *   <li>order*：txn_order(status=已收款) 直接按顾问汇总收款额/笔数——ORDER 收款口径；</li>
     *   <li>refund*：txn_refund(status=REFUNDED) 经 order_no JOIN 订单取顾问，汇总退款额/笔数，
     *       finance 侧按顾问负向冲减（退款扣回提成，竞品通行做法）。</li>
     * </ul>
     * JOIN 不到订单或订单无顾问的划扣/退款/收款无法归属，计入 unmatched* 计数随响应返回（诚实降级，
     * 不臆造归属）；不做 DataScope 收敛（由 finance 侧按登录人门店域二次过滤，与 finance-flows 同边界）。
     * 金额单位 Long「分」。
     */
    @GetMapping("/commission-base")
    @RequirePerm("internal:finance-flow")
    public Map<String, Object> commissionBase(
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "storeCode", required = false) String storeCode) {
        String ym;
        if (month == null || month.isBlank()) {
            ym = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString().substring(0, 7);
        } else {
            ym = month.trim().substring(0, Math.min(7, month.trim().length()));
            if (!ym.matches("\\d{4}-\\d{2}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "月份参数 month 格式非法，需 yyyy-MM 或 yyyy-MM-01（如 2026-09）：" + month);
            }
        }
        OffsetDateTime from = LocalDate.parse(ym + "-01").atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime to = from.plusMonths(1);
        String store = (storeCode == null || storeCode.isBlank()) ? null : storeCode.trim();

        List<WriteoffRecord> writeoffs = writeoffRepo.findAll(writeoffSpec(store, from, to));
        List<TxnOrder> paidOrders = orderRepo.findAll(orderSpec(store, from, to));
        List<TxnRefund> refunds = refundRepo.findAll(refundSpec(store, from, to));

        // orderNo → 订单映射：已收款订单直接入图；划扣/退款关联订单批量补齐（防 N+1）
        Map<String, TxnOrder> orderByNo = new LinkedHashMap<>();
        for (TxnOrder o : paidOrders) {
            if (o.getOrderNo() != null) orderByNo.putIfAbsent(o.getOrderNo(), o);
        }
        java.util.Set<String> missingNos = new java.util.HashSet<>();
        for (WriteoffRecord w : writeoffs) {
            if (w.getOrderNo() != null && !orderByNo.containsKey(w.getOrderNo())) missingNos.add(w.getOrderNo());
        }
        for (TxnRefund r : refunds) {
            if (r.getOrderNo() != null && !orderByNo.containsKey(r.getOrderNo())) missingNos.add(r.getOrderNo());
        }
        if (!missingNos.isEmpty()) {
            for (TxnOrder o : orderRepo.findByOrderNoIn(missingNos)) {
                if (o.getOrderNo() != null) orderByNo.putIfAbsent(o.getOrderNo(), o);
            }
        }

        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        int unmatchedWriteoff = 0;
        int unmatchedRefund = 0;
        int unmatchedOrder = 0;

        for (TxnOrder o : paidOrders) {
            String consultant = o.getConsultant();
            if (consultant == null || consultant.isBlank()) {
                unmatchedOrder++;
                continue;
            }
            Map<String, Object> row = commissionRow(rows, consultant, o.getStoreCode());
            row.put("orderAmount", (Long) row.get("orderAmount") + (o.getAmount() == null ? 0L : o.getAmount()));
            row.put("orderCount", (Integer) row.get("orderCount") + 1);
        }
        for (WriteoffRecord w : writeoffs) {
            TxnOrder o = w.getOrderNo() == null ? null : orderByNo.get(w.getOrderNo());
            String consultant = o == null ? null : o.getConsultant();
            if (consultant == null || consultant.isBlank()) {
                unmatchedWriteoff++;
                continue;
            }
            String sc = (w.getStoreCode() != null && !w.getStoreCode().isBlank()) ? w.getStoreCode() : o.getStoreCode();
            Map<String, Object> row = commissionRow(rows, consultant, sc);
            row.put("writeoffAmount", (Long) row.get("writeoffAmount") + (w.getAmount() == null ? 0L : w.getAmount()));
            row.put("writeoffCount", (Integer) row.get("writeoffCount") + 1);
        }
        for (TxnRefund r : refunds) {
            TxnOrder o = r.getOrderNo() == null ? null : orderByNo.get(r.getOrderNo());
            String consultant = o == null ? null : o.getConsultant();
            if (consultant == null || consultant.isBlank()) {
                unmatchedRefund++;
                continue;
            }
            String sc = (r.getStoreCode() != null && !r.getStoreCode().isBlank()) ? r.getStoreCode() : o.getStoreCode();
            Map<String, Object> row = commissionRow(rows, consultant, sc);
            row.put("refundAmount", (Long) row.get("refundAmount") + (r.getRefundAmt() == null ? 0L : r.getRefundAmt()));
            row.put("refundCount", (Integer) row.get("refundCount") + 1);
        }

        List<Map<String, Object>> rowList = rows.values().stream()
                .sorted((a, b) -> String.valueOf(a.get("staffId")).compareTo(String.valueOf(b.get("staffId"))))
                .toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("month", ym + "-01");
        resp.put("store", store == null ? "ALL" : store);
        resp.put("currency", "CNY");
        resp.put("unit", "分");
        resp.put("rows", rowList);
        resp.put("unmatchedWriteoffCount", unmatchedWriteoff);
        resp.put("unmatchedRefundCount", unmatchedRefund);
        resp.put("unmatchedOrderCount", unmatchedOrder);
        return resp;
    }

    /** 提成聚合行：按顾问工号取行（惰性初始化，分量单位分/笔）。 */
    private Map<String, Object> commissionRow(Map<String, Map<String, Object>> rows, String staffId, String storeCode) {
        Map<String, Object> row = rows.get(staffId);
        if (row == null) {
            row = new LinkedHashMap<>();
            row.put("staffId", staffId);
            row.put("storeCode", storeCode);
            row.put("writeoffAmount", 0L);
            row.put("writeoffCount", 0);
            row.put("orderAmount", 0L);
            row.put("orderCount", 0);
            row.put("refundAmount", 0L);
            row.put("refundCount", 0);
            rows.put(staffId, row);
        } else if ((row.get("storeCode") == null || row.get("storeCode").toString().isBlank()) && storeCode != null) {
            row.put("storeCode", storeCode);
        }
        return row;
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

    /**
     * 核销双签明细分页规格（B24 卡2）：不固化 status，支持状态/卡号/客户/关键词/时间区间组合过滤。
     * keyword 对核销号/订单号/卡号/项目名做 OR 模糊（其余精确条件在 AND 组内）。
     */
    private Specification<WriteoffRecord> writeoffDetailSpec(String storeCode, String status, String cardNo,
                                                             String customerId, String keyword,
                                                             OffsetDateTime from, OffsetDateTime to) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (storeCode != null) ps.add(cb.equal(root.get("storeCode"), storeCode));
            if (cardNo != null) ps.add(cb.equal(root.get("cardNo"), cardNo));
            if (customerId != null) ps.add(cb.equal(root.get("customerId"), customerId));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"), to));
            if (keyword != null) {
                String like = "%" + keyword.toUpperCase() + "%";
                ps.add(cb.or(cb.like(cb.upper(root.get("writeoffId")), like),
                        cb.like(cb.upper(root.get("orderNo")), like),
                        cb.like(cb.upper(root.get("cardNo")), like),
                        cb.like(cb.upper(root.get("project")), like)));
            }
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
