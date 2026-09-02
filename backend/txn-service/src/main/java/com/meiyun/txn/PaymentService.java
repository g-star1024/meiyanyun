package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 订单收款服务：支付明细流水（order_payment）+ 累积收款 / 现金找零。
 *
 * <p>规则对齐前端收银台 mock（活规格 backup-views-0830-0001/OrderView.vue）：
 * <ul>
 *   <li>仅「待收款」订单可收款；已收款幂等返回；其他状态拒绝。</li>
 *   <li>现金 cash：tendered 是客户实付，入账按待收封顶，找零 = 实付 − 入账；实付须 &gt; 0。</li>
 *   <li>非现金（wxpay/alipay/card/balance）：入账 = 实付，且累计不得超过应收（超额 400 拒绝）。</li>
 *   <li>累计入账 ≥ 应收 → 订单置「已收款」，并联动方案单 READY_PAY → PAID（解锁治疗）。</li>
 * </ul>
 * 每笔收款落 order_payment 流水 + audit_log PAY；金额单位「分」。
 */
@Service
public class PaymentService {

    private static final Set<String> METHODS = Set.of("cash", "card", "wxpay", "alipay", "balance");

    private final TxnOrderRepository orderRepo;
    private final OrderPaymentRepository payRepo;
    private final ConsultPlanService planService;
    private final AuditRecorder audit;

    public PaymentService(TxnOrderRepository orderRepo, OrderPaymentRepository payRepo,
                          ConsultPlanService planService, AuditRecorder audit) {
        this.orderRepo = orderRepo;
        this.payRepo = payRepo;
        this.planService = planService;
        this.audit = audit;
    }

    /** 单笔支付流水读模型。 */
    public record PaymentView(String paymentId, String orderNo, String payMethod,
                              Long cashTendered, Long postedAmount, Long changeAmount,
                              Long paidAfter, String operator) {}

    /** 收款结果：本笔流水 + 入账后累计/找零 + 订单是否收齐。 */
    public record PayResult(PaymentView payment, Long orderAmount, Long paidAmount,
                            Long changeAmount, boolean completed, String orderStatus) {}

    /**
     * 登记一笔收款。
     *
     * @param method   支付方式 cash/card/wxpay/alipay/balance
     * @param tendered 客户实付（分）；现金为递交现金，非现金等于实际扣款
     * @param operator 收银员
     */
    @Transactional
    public PayResult pay(String orderNo, String method, Long tendered, String operator) {
        // 操作人一律取 JWT 登录人工号（请求体 operator 不可信，忽略）；无上下文回落 system
        operator = DataScope.currentActor();
        TxnOrder o = orderRepo.findById(orderNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在: " + orderNo));

        // 幂等：已收款直接返回当前态（不重复记流水）
        if ("已收款".equals(o.getStatus())) {
            List<OrderPayment> pays = payRepo.findByOrderNoOrderByPaymentIdAsc(orderNo);
            long paid = pays.stream().mapToLong(OrderPayment::getPostedAmount).sum();
            long change = pays.stream().mapToLong(OrderPayment::getChangeAmount).sum();
            return new PayResult(null, o.getAmount(), paid, change, true, o.getStatus());
        }
        if (!"待收款".equals(o.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "订单状态须为「待收款」才可收款，当前: " + o.getStatus());
        }
        if (method == null || method.isBlank() || !METHODS.contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "支付方式无效: " + method + "（支持 cash/card/wxpay/alipay/balance）");
        }
        long t = tendered == null ? 0L : tendered;
        if (t <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收款金额必须为正（单位：分）");
        }

        long paidBefore = payRepo.findByOrderNoOrderByPaymentIdAsc(orderNo).stream()
                .mapToLong(OrderPayment::getPostedAmount).sum();
        long rest = o.getAmount() - paidBefore;
        if (rest <= 0) {
            // 流水已收齐但状态未更新（兜底）：直接置已收款
            o.setStatus("已收款");
            orderRepo.save(o);
            planService.markPaidByOrder(orderNo, operator);
            return new PayResult(null, o.getAmount(), paidBefore, 0L, true, o.getStatus());
        }

        boolean cash = "cash".equals(method);
        long posted;
        long change;
        if (cash) {
            // 现金：入账按待收封顶，找零 = 实付 - 入账
            posted = Math.min(t, rest);
            change = Math.max(0L, t - posted);
        } else {
            // 非现金：实付即入账，不得超额
            if (t > rest) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "收款超额：本单待收 " + rest + " 分，本次 " + method + " " + t
                                + " 分将导致超收；请改为现金或调整金额（非现金不支持找零）");
            }
            posted = t;
            change = 0L;
        }

        long paidAfter = paidBefore + posted;
        boolean completed = paidAfter >= o.getAmount();

        OrderPayment p = new OrderPayment();
        p.setPaymentId(nextPaymentNo());
        p.setOrderNo(orderNo);
        p.setPayMethod(method);
        p.setCashTendered(t);
        p.setPostedAmount(posted);
        p.setChangeAmount(change);
        p.setPaidAfter(paidAfter);
        p.setOperator(operator);
        payRepo.save(p);

        if (completed) {
            o.setStatus("已收款");
            orderRepo.save(o);
        }

        audit.record("ORDER", orderNo, operator == null ? "system" : operator, "PAY",
                "{\"method\":\"" + method + "\",\"tendered\":" + t + ",\"posted\":" + posted
                        + ",\"change\":" + change + ",\"paidAfter\":" + paidAfter
                        + ",\"completed\":" + completed + "}");

        if (completed) {
            // 诊疗方案单联动：READY_PAY → PAID（零售单无方案单，内部空操作）
            planService.markPaidByOrder(orderNo, operator);
        }

        return new PayResult(toView(p), o.getAmount(), paidAfter, change, completed, o.getStatus());
    }

    /** 某订单的全部支付流水。 */
    @Transactional(readOnly = true)
    public List<PaymentView> listByOrder(String orderNo) {
        return payRepo.findByOrderNoOrderByPaymentIdAsc(orderNo).stream().map(this::toView).toList();
    }

    /** 批量取一批订单的支付流水，按 orderNo 归组（供订单列表富化，防 N+1）。 */
    @Transactional(readOnly = true)
    public java.util.Map<String, List<OrderPayment>> loadByOrders(List<String> orderNos) {
        java.util.Map<String, List<OrderPayment>> map = new java.util.LinkedHashMap<>();
        if (orderNos == null || orderNos.isEmpty()) return map;
        payRepo.findByOrderNoInOrderByPaymentIdAsc(orderNos)
                .forEach(p -> map.computeIfAbsent(p.getOrderNo(), k -> new java.util.ArrayList<>()).add(p));
        return map;
    }

    private PaymentView toView(OrderPayment p) {
        return new PaymentView(p.getPaymentId(), p.getOrderNo(), p.getPayMethod(),
                p.getCashTendered(), p.getPostedAmount(), p.getChangeAmount(),
                p.getPaidAfter(), p.getOperator());
    }

    private synchronized String nextPaymentNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long seq = payRepo.maxSeqOfDay("PM" + day + "-%") + 1;
        return "PM" + day + "-" + String.format("%06d", seq);
    }
}
