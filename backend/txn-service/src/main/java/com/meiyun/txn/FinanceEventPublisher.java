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
 *   <li>退款终审 REFUND_CONFIRMED（B4.1 按支付构成拆分）：原单含储值余额支付时，余额部分优先回加——
 *       RF-DEPOSIT/IN/ERP/balance（退款冲回预收，预收池回增，不走商户渠道镜像）；法币部分
 *       RF-REFUND/OUT/CASHIER（CASH→cash、TRANSFER→transfer、ORIGINAL→仅反查非 balance 收款主渠道，
 *       反查不到保守 null）。余额回加额 = min(原单 balance 实付, 退款额)，法币退 = 退款额 − 余额回加；</li>
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
     * 退款终审（B4.1 按支付构成拆分）：余额部分回加储值 RF-DEPOSIT/IN/ERP/balance（冲回预收），
     * 法币部分 RF-REFUND/OUT/CASHIER（渠道只反查非 balance 收款流水）。任一分录额为 0 跳过。
     * 卡台账回加由 TxnService 另调 customer 端点（{@link #balanceRefundForOrder} 给额），本处只落资金分录。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void emitRefundConfirmed(TxnRefund r) {
        long refundAmt = r.getRefundAmt() == null ? 0L : r.getRefundAmt();
        long balanceRefund = balanceRefundForOrder(r.getOrderNo(), refundAmt);
        long cashRefund = refundAmt - balanceRefund;
        String who = nz(r.getCustomerName(), r.getTxnNo());
        List<Map<String, Object>> cmds = new ArrayList<>();
        if (balanceRefund > 0) {
            cmds.add(entry("REFUND-BALANCE:" + r.getTxnNo(), r.getTxnNo(), "REFUND",
                    "RF-DEPOSIT", "IN", balanceRefund, "balance", "ERP", "REFUND",
                    r.getStoreCode(), "退款回加储值（冲回预收）· " + who));
        }
        if (cashRefund > 0) {
            cmds.add(entry("REFUND-PAID:" + r.getTxnNo(), r.getTxnNo(), "REFUND",
                    "RF-REFUND", "OUT", cashRefund, refundChannel(r.getChannel(), r.getOrderNo()),
                    "CASHIER", "REFUND",
                    r.getStoreCode(), "退款支出 · " + who));
        }
        enqueue("REFUND_CONFIRMED", r.getTxnNo(), cmds);
    }

    /**
     * 本单退款应回加储值的金额：原单储值余额实付（order_payment payMethod=balance 的 postedAmount 合计，
     * PaymentService 保证同单仅一笔）与本次退款额取小——部分退款优先回加余额，避免多退给储值、法币退负。
     * 无 balance 支付或退款额 ≤ 0 返回 0（纯法币退款，不联动客户卡）。
     */
    public long balanceRefundForOrder(String orderNo, long refundAmt) {
        if (orderNo == null || orderNo.isBlank() || refundAmt <= 0) return 0L;
        long balancePart = payRepo.findByOrderNoOrderByPaymentIdAsc(orderNo).stream()
                .filter(p -> "balance".equals(p.getPayMethod()))
                .mapToLong(p -> p.getPostedAmount() == null ? 0L : p.getPostedAmount())
                .sum();
        return Math.min(balancePart, refundAmt);
    }

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
     * 退款法币渠道映射（B4.1）：CASH→cash、TRANSFER→transfer、ORIGINAL→反查原单<b>非 balance</b>
     * 收款主渠道（余额部分已由 RF-DEPOSIT/IN 回加，法币退款不得记 channel=balance 污染渠道账户镜像）；
     * 原单仅 balance 支付（纯余额单，法币退额必为 0，此路实际不会走到）或查不到 → null（落未标记渠道）。
     */
    private String refundChannel(String channel, String orderNo) {
        if ("CASH".equals(channel)) return "cash";
        if ("TRANSFER".equals(channel)) return "transfer";
        if ("ORIGINAL".equals(channel) && orderNo != null && !orderNo.isBlank()) {
            List<OrderPayment> cashPays = payRepo.findByOrderNoOrderByPaymentIdAsc(orderNo).stream()
                    .filter(p -> !"balance".equals(p.getPayMethod()))
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
