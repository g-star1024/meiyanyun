package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金事件发布器（B3 合规写）：收款收齐 / 退款退卡终审 / 卡扣划扣完成时，在业务事务内
 * 同写 {@link FinanceEvent}（PENDING），{@link FinanceEventRetryJob} 异步投递 finance 落账。
 *
 * <p>分录口径与 finance 侧 FundEntryService / FinanceAggregationService 逐字对齐（memo、科目、
 * 渠道、source、refType），保双跑期 ledger-diff 净额零差异：
 * <ul>
 *   <li>订单收齐 ORDER_PAID → RF-REVENUE/IN/CASHIER/ORDER，渠道取入账额最大一笔（混合支付 memo 标注）；</li>
 *   <li>退款终审 REFUND_CONFIRMED（B39 按「赠金→卡本金→法币」级联拆分）：赠金段不产生资金分录
 *       （门店让渡权益、无资金进出，与收款侧 grant 支付无专属分录对称，券回加走营销域 HTTP 联动）；
 *       卡本金段回加 RF-DEPOSIT/IN/ERP/balance（退款冲回预收，预收池回增，不走商户渠道镜像）；
 *       法币段 RF-REFUND/OUT/CASHIER（CASH→cash、TRANSFER→transfer、ORIGINAL→仅反查非 balance/grant
 *       收款主渠道，反查不到保守 null）。三段拆分见 {@link #refundSplitForOrder}；</li>
 *   <li>退卡终审 CARD_CANCEL_CONFIRMED → 三条：实退现金 RF-REFUND/OUT（refundAmt，渠道同上但 CC 无订单号，
 *       ORIGINAL 保守 null）+ 全额冲预收 RF-DEPOSIT/OUT/ERP（balance）+ 违约金转收入 RF-REVENUE/IN/ERP（fee，
 *       fee=0 跳过），依实体恒等式 balance = refundAmt + fee；</li>
 *   <li>卡扣划扣 WRITEOFF_DONE → 成对 RF-DEPOSIT/OUT/ERP（预收转出）+ RF-REVENUE/IN/ERP（确认收入），
 *       channel=null 内部结转；划扣额 ≤ 0（纯扣次/卡无余额）不产生资金动账，整体跳过。</li>
 * </ul>
 */
@Service
public class FinanceEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(FinanceEventPublisher.class);

    private final FinanceEventRepository eventRepo;
    private final OrderPaymentRepository payRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FinanceEventPublisher(FinanceEventRepository eventRepo, OrderPaymentRepository payRepo) {
        this.eventRepo = eventRepo;
        this.payRepo = payRepo;
    }

    /**
     * 订单收齐：整单收入分录（渠道=最大笔收款渠道，一单多渠道 memo 加混合标记）；
     * 若含储值余额（balance）支付，另补一条 RF-DEPOSIT/OUT（预收转出/渠道 balance，DESIGN §6.2）——
     * 余额消费时资金不进出商户户（余额已是预收负债），预收池须随确认收入同步扣减。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void emitOrderPaid(TxnOrder o) {
        List<OrderPayment> pays = payRepo.findByOrderNoOrderByPaymentIdAsc(o.getOrderNo());
        PayChannel pc = resolvePayChannel(pays);
        long balancePart = pays.stream()
                .filter(p -> "balance".equals(p.getPayMethod()))
                .mapToLong(p -> p.getPostedAmount() == null ? 0L : p.getPostedAmount())
                .sum();
        List<Map<String, Object>> cmds = new ArrayList<>();
        if (balancePart > 0) {
            cmds.add(entry("BALANCE-CONSUME:" + o.getOrderNo(), o.getOrderNo(), "ORDER",
                    "RF-DEPOSIT", "OUT", balancePart, "balance", "CASHIER", "ORDER",
                    o.getStoreCode(), "储值余额消费（预收转出）· " + nz(o.getProject(), o.getOrderNo())));
        }
        String memo = "订单收款 · " + nz(o.getProject(), o.getOrderNo());
        if (pc.mixed()) memo = memo + "（混合支付）";
        cmds.add(entry(
                "ORDER-PAID:" + o.getOrderNo(), o.getOrderNo(), "ORDER",
                "RF-REVENUE", "IN", o.getAmount(), pc.method(), "CASHIER", "ORDER",
                o.getStoreCode(), memo));
        enqueue("ORDER_PAID", o.getOrderNo(), cmds);
    }

    /**
     * 售卡订单收齐（B16）：售卡是预收负债而非即时收入，落单条 RF-DEPOSIT/IN/CASHIER 预收分录
     * （不发 RF-REVENUE），finance 侧 applyPrepayPool 自动同增预收池 total/pendingConsume、
     * applyAccountMirror 按渠道联动商户户镜像；后续疗程/储值核销走现成 emitWriteoffDone
     * （RF-DEPOSIT/OUT + RF-REVENUE/IN 成对结转）。渠道取最大笔法币收款（售卡禁 balance，
     * PaymentService 已拦截，故全部为法币渠道）。idemKey=CARD-SALE:订单号，重试不重复落账。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void emitCardSalePaid(TxnOrder o) {
        List<OrderPayment> pays = payRepo.findByOrderNoOrderByPaymentIdAsc(o.getOrderNo());
        PayChannel pc = resolvePayChannel(pays);
        String memo = "售卡预收 · " + nz(o.getProject(), o.getOrderNo());
        if (pc.mixed()) memo = memo + "（混合支付）";
        List<Map<String, Object>> cmds = List.of(
                entry("CARD-SALE:" + o.getOrderNo(), o.getOrderNo(), "ORDER",
                        "RF-DEPOSIT", "IN", o.getAmount(), pc.method(), "CASHIER", "ORDER",
                        o.getStoreCode(), memo));
        enqueue("CARD_SALE_PAID", o.getOrderNo(), cmds);
    }

    /**
     * 退款终审（B39 三段级联拆分）：赠金段→卡本金段→法币段。
     * <ul>
     *   <li>赠金段（grant）不出任何资金分录：赠金是门店让渡权益、无资金进出商户户，与收款侧
     *       grant 支付无专属分录对称；券回加与 REFUND 流水由 TxnService 联动营销域完成；</li>
     *   <li>卡本金段（balance）回加储值 RF-DEPOSIT/IN/ERP/balance（冲回预收），卡台账回加由
     *       TxnService 另调客户域端点（{@link #refundSplitForOrder} 给额）；</li>
     *   <li>法币段 RF-REFUND/OUT/CASHIER（渠道只反查非 balance/grant 收款流水）。</li>
     * </ul>
     * 任一分录额为 0 跳过；三段全为 0（纯赠金单退款）不投递资金事件。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void emitRefundConfirmed(TxnRefund r) {
        long refundAmt = r.getRefundAmt() == null ? 0L : r.getRefundAmt();
        RefundSplit split = refundSplitForOrder(r.getOrderNo(), refundAmt);
        String who = nz(r.getCustomerName(), r.getTxnNo());
        List<Map<String, Object>> cmds = new ArrayList<>();
        if (split.balance() > 0) {
            cmds.add(entry("REFUND-BALANCE:" + r.getTxnNo(), r.getTxnNo(), "REFUND",
                    "RF-DEPOSIT", "IN", split.balance(), "balance", "ERP", "REFUND",
                    r.getStoreCode(), "退款回加储值（冲回预收）· " + who));
        }
        if (split.cash() > 0) {
            cmds.add(entry("REFUND-PAID:" + r.getTxnNo(), r.getTxnNo(), "REFUND",
                    "RF-REFUND", "OUT", split.cash(), refundChannel(r.getChannel(), r.getOrderNo()),
                    "CASHIER", "REFUND",
                    r.getStoreCode(), "退款支出 · " + who));
        }
        if (cmds.isEmpty()) {
            log.info("退款终审 {} 无资金动账（赠金段 {} 分不产生分录），跳过资金事件", r.getTxnNo(), split.grant());
            return;
        }
        enqueue("REFUND_CONFIRMED", r.getTxnNo(), cmds);
    }

    /**
     * 本单退款按「赠金→卡本金→法币」级联拆分（B39），TxnService 远程联动（营销回加/卡回加）
     * 与本类资金分录必须共用同一结果，避免两边口径漂移。
     * 赠金段 = min(原单 grant 实付合计, 退款额)；卡本金段 = min(原单 balance 实付合计, 剩余)；
     * 法币段 = 退款额 − 前两段。退款额 ≤ 0 返回全 0。
     */
    public RefundSplit refundSplitForOrder(String orderNo, long refundAmt) {
        if (orderNo == null || orderNo.isBlank() || refundAmt <= 0) {
            return new RefundSplit(0, 0, 0);
        }
        long grantPaid = 0;
        long balancePaid = 0;
        for (OrderPayment p : payRepo.findByOrderNoOrderByPaymentIdAsc(orderNo)) {
            long posted = p.getPostedAmount() == null ? 0L : p.getPostedAmount();
            if ("grant".equals(p.getPayMethod())) {
                grantPaid += posted;
            } else if ("balance".equals(p.getPayMethod())) {
                balancePaid += posted;
            }
        }
        long grantPart = Math.min(grantPaid, refundAmt);
        long balancePart = Math.min(balancePaid, refundAmt - grantPart);
        long cashPart = refundAmt - grantPart - balancePart;
        return new RefundSplit(grantPart, balancePart, cashPart);
    }

    /**
     * 本单退款应回加储值的金额：委托 {@link #refundSplitForOrder} 的卡本金段（B39 后口径为
     * 「先冲赠金、再回余额」，保留本方法供既有调用方使用）。
     */
    public long balanceRefundForOrder(String orderNo, long refundAmt) {
        return refundSplitForOrder(orderNo, refundAmt).balance();
    }

    /** 退款三段拆分结果（单位：分）：赠金段 / 卡本金段 / 法币段，三者之和 = 本次退款额。 */
    public record RefundSplit(long grant, long balance, long cash) {}

    /**
     * 退卡终审：依恒等式 balance = refundAmt + fee 落三条分录。
     * 实退（现金渠道）+ 全额冲预收（ERP 内部结转）+ 违约金转收入（ERP；fee=0 跳过）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void emitCardCancelConfirmed(TxnCardCancel c) {
        // CC 无订单号，ORIGINAL 无法反查原单渠道，保守 null（落未标记渠道，不臆造）
        String channel = "CASH".equals(c.getChannel()) ? "cash"
                : "TRANSFER".equals(c.getChannel()) ? "transfer" : null;
        List<Map<String, Object>> cmds = new ArrayList<>();
        cmds.add(entry("CARD-CANCEL-REFUND:" + c.getTxnNo(), c.getTxnNo(), "REFUND",
                "RF-REFUND", "OUT", c.getRefundAmt(), channel, "CASHIER", "REFUND",
                c.getStoreCode(), "退款支出 · " + nz(c.getCustomerName(), c.getTxnNo())));
        cmds.add(entry("CARD-CANCEL-DEPOSIT:" + c.getTxnNo(), c.getTxnNo(), "REFUND",
                "RF-DEPOSIT", "OUT", c.getBalance(), null, "ERP", "REFUND",
                c.getStoreCode(), "退卡冲预收 · " + nz(c.getCustomerName(), c.getTxnNo())));
        if (c.getFee() != null && c.getFee() > 0) {
            cmds.add(entry("CARD-CANCEL-FEE:" + c.getTxnNo(), c.getTxnNo(), "REFUND",
                    "RF-REVENUE", "IN", c.getFee(), null, "ERP", "REFUND",
                    c.getStoreCode(), "退卡违约金收入 · " + nz(c.getCustomerName(), c.getTxnNo())));
        }
        enqueue("CARD_CANCEL_CONFIRMED", c.getTxnNo(), cmds);
    }

    /**
     * 卡扣划扣完成：成对预收转出 / 确认收入分录（ERP 内部结转，channel=null）。
     * 划扣额 ≤ 0（纯扣次或卡无余额）无资金动账，整体跳过不投递。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void emitWriteoffDone(WriteoffRecord w) {
        if (w.getAmount() == null || w.getAmount() <= 0) {
            log.info("划扣 {} 金额为 {}（纯扣次/卡无余额），无资金动账，跳过落账事件", w.getWriteoffId(), w.getAmount());
            return;
        }
        String project = nz(w.getProject(), w.getWriteoffId());
        List<Map<String, Object>> cmds = List.of(
                entry("WRITEOFF-DEPOSIT:" + w.getWriteoffId(), w.getWriteoffId(), "WRITEOFF",
                        "RF-DEPOSIT", "OUT", w.getAmount(), null, "ERP", "WRITEOFF",
                        w.getStoreCode(), "卡划扣（预收转出）· " + project),
                entry("WRITEOFF-REVENUE:" + w.getWriteoffId(), w.getWriteoffId(), "WRITEOFF",
                        "RF-REVENUE", "IN", w.getAmount(), null, "ERP", "WRITEOFF",
                        w.getStoreCode(), "划扣确认收入 · " + project));
        enqueue("WRITEOFF_DONE", w.getWriteoffId(), cmds);
    }

    /**
     * 耗材出库终审（B5 成本库存）：领用（USE）→ TK-MATERIAL（耗材成本），报损（SCRAP）→ TK-LOSS（损耗成本）。
     * 方向 OUT（成本发生）、source=ERP（内部结转，不联动渠道镜像/预收池）、channel=null、bizType/refType=COST。
     * 成本额 ≤ 0（移动平均成本为 0 或扣库零金额）不产生成本动账，整体跳过不投递。
     * 注：finance 侧 FundEntryService 白名单须同步扩 TK-MATERIAL/TK-LOSS 科目与 COST 业务类型（B5-2c），
     * 否则事件投递会 retry 后 DEAD。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void emitConsumableCost(String todoNo, String storeCode, String moveType, long totalFen) {
        if (totalFen <= 0) {
            log.info("耗材出库 {} 成本额为 {}（零金额），无成本动账，跳过落账事件", todoNo, totalFen);
            return;
        }
        boolean scrap = "SCRAP".equals(moveType);
        String subject = scrap ? "TK-LOSS" : "TK-MATERIAL";
        String memo = (scrap ? "耗材报损出库 · " : "耗材领用出库 · ") + todoNo;
        String eventType = scrap ? "CONSUMABLE_SCRAP" : "CONSUMABLE_USE";
        List<Map<String, Object>> cmds = List.of(
                entry("CONSUMABLE-COST:" + todoNo, todoNo, "COST",
                        subject, "OUT", totalFen, null, "ERP", "COST",
                        storeCode, memo));
        enqueue(eventType, todoNo, cmds);
    }

    // ==================== 内部 ====================

    /** 同事务落 PENDING 事件（负载序列化失败属编程错误，快速失败回滚业务）。 */
    private void enqueue(String eventType, String bizRef, List<Map<String, Object>> cmds) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(cmds);
        } catch (Exception e) {
            log.error("资金事件负载序列化失败 eventType={} bizRef={}: {}", eventType, bizRef, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "资金事件内容序列化失败");
        }
        FinanceEvent e = new FinanceEvent();
        e.setEventType(eventType);
        e.setBizRef(bizRef);
        e.setPayload(payload);
        e.setStatus("PENDING");
        eventRepo.save(e);
        log.info("资金事件已入 outbox eventType={} bizRef={} entries={}", eventType, bizRef, cmds.size());
    }

    /** 构建一条与 finance FundEntryCmd 契约逐字段对齐的分录 Map（occurredAt 留空，finance 取受理时刻）。 */
    private Map<String, Object> entry(String idemKey, String bizRef, String bizType, String subject,
                                      String direction, Long amount, String channel, String source,
                                      String refType, String storeCode, String memo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idemKey", idemKey);
        m.put("bizRef", bizRef);
        m.put("bizType", bizType);
        m.put("subject", subject);
        m.put("direction", direction);
        m.put("amount", amount);
        m.put("channel", channel);
        m.put("source", source);
        m.put("refType", refType);
        m.put("storeCode", storeCode);
        m.put("memo", memo);
        m.put("occurredAt", null);
        return m;
    }

    /**
     * 退款法币渠道映射（B39）：CASH→cash、TRANSFER→transfer、ORIGINAL→反查原单<b>法币</b>
     * 收款主渠道（同时排除 balance 与 grant——前者已由 RF-DEPOSIT/IN 回加，后者不产生资金分录，
     * 法币退款若记 channel=balance/grant 会污染渠道账户镜像，grant 更是 finance 渠道白名单外的非法值）；
     * 原单仅 balance/grant 支付（纯余额/纯赠金单，法币退额必为 0，此路实际不会走到）或查不到 → null。
     */
    private String refundChannel(String channel, String orderNo) {
        if ("CASH".equals(channel)) return "cash";
        if ("TRANSFER".equals(channel)) return "transfer";
        if ("ORIGINAL".equals(channel) && orderNo != null && !orderNo.isBlank()) {
            List<OrderPayment> cashPays = payRepo.findByOrderNoOrderByPaymentIdAsc(orderNo).stream()
                    .filter(p -> !"balance".equals(p.getPayMethod()) && !"grant".equals(p.getPayMethod()))
                    .toList();
            return resolvePayChannel(cashPays).method();
        }
        return null;
    }

    /**
     * 混合支付口径（设计 §4.1 方案 a）：渠道取 postedAmount（入账额）最大一笔的 payMethod；
     * 不同渠道数 &gt; 1 置 mixed=true。无支付流水 → method=null、mixed=false。
     */
    private PayChannel resolvePayChannel(List<OrderPayment> pays) {
        if (pays == null || pays.isEmpty()) return new PayChannel(null, false);
        String main = null;
        long maxPosted = Long.MIN_VALUE;
        for (OrderPayment p : pays) {
            long posted = p.getPostedAmount() == null ? 0L : p.getPostedAmount();
            if (posted > maxPosted) {
                maxPosted = posted;
                main = p.getPayMethod();
            }
        }
        boolean mixed = pays.stream().map(OrderPayment::getPayMethod).distinct().count() > 1;
        return new PayChannel(main, mixed);
    }

    private record PayChannel(String method, boolean mixed) {}

    private static String nz(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
