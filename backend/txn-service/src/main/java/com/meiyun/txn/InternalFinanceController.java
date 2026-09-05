package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * finance-service 聚合数据源（服务间内部端点，非业务页面）。
 *
 * <p>红线边界：本端点仅向 finance-service 以系统身份（X-Internal-Token）开放只读投影，
 * 普通登录人无 {@code internal:finance-flow} 权限 → 403。财务聚合不直接 JOIN/直读交易表，
 * 由交易域按自身实体语义提供资金流水，微服务按域拆库后零改动成立。
 *
 * <p>过滤：storeCode / from / to（yyyy-MM-dd，按 created_at 闭区间）均可选；内部身份为 GROUP 域，
 * 数据权限收敛由 finance-service 调用后按登录人门店域二次过滤（边界服务已强制自身 DataScope）。
 */
@RestController
@RequestMapping("/api/txn/internal")
public class InternalFinanceController {

    private final TxnOrderRepository orderRepo;
    private final TxnRefundRepository refundRepo;
    private final WriteoffRepository writeoffRepo;
    private final TxnCardCancelRepository cardCancelRepo;
    private final PaymentService paymentService;

    public InternalFinanceController(TxnOrderRepository orderRepo, TxnRefundRepository refundRepo,
                                     WriteoffRepository writeoffRepo, TxnCardCancelRepository cardCancelRepo,
                                     PaymentService paymentService) {
        this.orderRepo = orderRepo;
        this.refundRepo = refundRepo;
        this.writeoffRepo = writeoffRepo;
        this.cardCancelRepo = cardCancelRepo;
        this.paymentService = paymentService;
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
