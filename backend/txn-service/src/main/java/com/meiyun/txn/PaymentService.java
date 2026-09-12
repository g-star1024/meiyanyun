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

    private static final Set<String> METHODS = Set.of("cash", "card", "wxpay", "alipay", "balance", "grant");

    private final TxnOrderRepository orderRepo;
    private final OrderPaymentRepository payRepo;
    private final ConsultPlanService planService;
    private final AuditRecorder audit;
    private final FinanceEventPublisher financeEvents;
    private final CustomerCardClient cardClient;
    private final MarketingGrantClient grantClient;

    public PaymentService(TxnOrderRepository orderRepo, OrderPaymentRepository payRepo,
                          ConsultPlanService planService, AuditRecorder audit,
                          FinanceEventPublisher financeEvents, CustomerCardClient cardClient,
                          MarketingGrantClient grantClient) {
        this.orderRepo = orderRepo;
        this.payRepo = payRepo;
        this.planService = planService;
        this.audit = audit;
        this.financeEvents = financeEvents;
        this.cardClient = cardClient;
        this.grantClient = grantClient;
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
     * @param method   支付方式 cash/card/wxpay/alipay/balance/grant
     * @param tendered 客户实付（分）；现金为递交现金，非现金等于实际扣款
     * @param operator 收银员
     * @param cardNo   储值余额支付（balance）时的会员卡号；其他方式忽略
     */
    @Transactional
    public PayResult pay(String orderNo, String method, Long tendered, String operator, String cardNo) {
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
                    "支付方式无效: " + method + "（支持 cash/card/wxpay/alipay/balance/grant）");
        }
        if ("CARD_SALE".equals(o.getBizKind()) && "balance".equals(method)) {
            // 售卡禁储值余额支付：售卡本身是预收负债（RF-DEPOSIT/IN），用余额买卡会「预收转增、无实款进商户户」，
            // 绕开资金闭环（卡买卡套现）；售卡须以法币现金/刷卡/微信/支付宝实付。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "购卡 / 开卡订单不能使用储值余额支付（售卡须实付现金/刷卡/微信/支付宝），请更换支付方式");
        }
        if ("CARD_SALE".equals(o.getBizKind()) && "grant".equals(method)) {
            // 售卡禁营销赠金：赠金是营销费用形成的负债，用它买卡等于「负债转预收、无实款进商户户」，
            // 与储值买卡同类套现路径，且会虚增储值池。售卡须以法币实付。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "购卡 / 开卡订单不能使用营销赠金抵扣（售卡须实付现金/刷卡/微信/支付宝），请更换支付方式");
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
            if ("CARD_SALE".equals(o.getBizKind())) {
                // 售卡单兜底收齐：同事务开卡（sale_no 幂等）+ 预收分录（idem_key 幂等）
                issueCardForSale(o, operator);
                financeEvents.emitCardSalePaid(o);
            } else {
                // B3 合规写：收齐同事务入资金事件 outbox（幂等：finance 侧 idem_key 去重）
                financeEvents.emitOrderPaid(o);
            }
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

        if ("balance".equals(method)) {
            // B4 储值实扣：card_ledger 以 orderNo 为幂等键（一单只能一笔储值扣款，重放不双扣）
            if (cardNo == null || cardNo.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "储值余额支付必须提供会员卡号 cardNo");
            }
            boolean alreadyBalance = payRepo.findByOrderNoOrderByPaymentIdAsc(orderNo).stream()
                    .anyMatch(p -> "balance".equals(p.getPayMethod()));
            if (alreadyBalance) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "本订单已使用储值余额支付过一笔（储值扣款按订单幂等，不支持同单分次储值）；"
                                + "剩余待收请改用现金/微信/支付宝/银行卡");
            }
            // 顺序（DESIGN §6.1）：先扣卡（customer）→ 再写 order_payment → 收齐联动。
            // 余额不足 customer 抛 422 中文；跨服务失败抛异常 → 本事务整体回滚，绝不出现「收款成功但卡没扣」。
            cardClient.consume(cardNo.trim(), o.getCustomerId(), posted, orderNo);
        }

        if ("grant".equals(method)) {
            // B35 赠金实扣：grant_deduction 以 orderNo 为幂等键（一单只能一笔赠金，重放不双扣），
            // 与储值同口径。赠金归属客户，故订单必须有客户（散客单无从抵扣）。
            if (o.getCustomerId() == null || o.getCustomerId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "营销赠金抵扣需要订单关联客户，散客订单请改用现金/微信/支付宝/银行卡");
            }
            boolean alreadyGrant = payRepo.findByOrderNoOrderByPaymentIdAsc(orderNo).stream()
                    .anyMatch(p -> "grant".equals(p.getPayMethod()));
            if (alreadyGrant) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "本订单已使用营销赠金抵扣过一笔（赠金抵扣按订单幂等，不支持同单分次抵扣）；"
                                + "剩余待收请改用现金/微信/支付宝/银行卡");
            }
            // 顺序同储值：先扣赠金（marketing）→ 再写 order_payment → 收齐联动。
            // 余额不足营销域抛 422 中文；跨服务失败抛异常 → 本事务整体回滚，绝不出现「收款成功但赠金没扣」。
            grantClient.deduct(o.getCustomerId(), posted, orderNo, o.getStoreCode(), operator);
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
            if ("CARD_SALE".equals(o.getBizKind())) {
                // B16 售卡收齐：同事务回调 customer 开卡（member_card 实例 + 首笔 RECHARGE 流水，
                // sale_no=orderNo 幂等）；开卡失败（客户 404 / customer 不可用）抛异常整笔回滚，
                // 绝不出现「收款办结但卡未开」。资金走 RF-DEPOSIT/IN 预收（不发 RF-REVENUE）。
                issueCardForSale(o, operator);
                financeEvents.emitCardSalePaid(o);
            } else {
                // B3 合规写：收齐同事务入资金事件 outbox（订单收款收入分录，渠道=最大笔收款渠道）
                financeEvents.emitOrderPaid(o);
            }
        }

        return new PayResult(toView(p), o.getAmount(), paidAfter, change, completed, o.getStatus());
    }

    /**
     * 售卡收齐同事务开卡：以订单上的模板快照（productCode/cardType/totalTimes/validityDays）
     * 调 customer 开卡。customer 以 sale_no=orderNo 幂等，收款/重试重放不重复开卡；
     * giftBalance 本批固定 0（catalog 暂无赠金字段），售价=订单金额。开卡后不回写订单。
     */
    private void issueCardForSale(TxnOrder o, String operator) {
        int totalTimes = o.getCardTotalTimes() == null || o.getCardTotalTimes() < 1
                ? 1 : o.getCardTotalTimes();
        int validityDays = o.getCardValidityDays() == null || o.getCardValidityDays() < 0
                ? 0 : o.getCardValidityDays();
        String cardType = o.getCardType() == null ? "" : o.getCardType();
        cardClient.issueCard(o.getOrderNo(), o.getCustomerId(), o.getStoreCode(),
                o.getProductCode() == null ? "" : o.getProductCode(), cardType,
                o.getProject(), totalTimes, validityDays,
                o.getAmount() == null ? 0L : o.getAmount(), 0L,
                operator == null ? "system" : operator);
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
