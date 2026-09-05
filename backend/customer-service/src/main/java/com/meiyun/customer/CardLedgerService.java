package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 储值卡台账服务（B4 充值储值 / B4.1 退款回加）：充值加款 / 余额消费扣款 / 订单退款回加 / 退卡清零，
 * 均 append-only 写 {@link CardLedger} 并同步 {@link MemberCard#getBalance()} 终值，对账恒等式
 * Σ card_ledger.amount = member_card.balance。
 *
 * <p>写接口四件套：①校验（金额/支付方式/卡状态/余额/防超退，中文拒绝）；②幂等（充值 RC 单号重放 409，
 * 内部扣款/退款回加/退卡按来源单号重放返回成功防双扣双加）；③全审计（audit 落 CARD 链）；④并发安全
 * （{@link MemberCardRepository#findForUpdate} 行锁 + synchronized 单号生成）。
 *
 * <p>资金联动：充值成功同事务经 {@link CardFinanceEventPublisher} 落 outbox（RF-DEPOSIT/IN）；
 * 消费/退款回加/退卡的资金分录由 txn 域 outbox 投递，本域只动卡台账。
 */
@Service
public class CardLedgerService {

    /** 充值支付方式白名单（充值不能用储值余额 balance，防自我充值套现）。 */
    private static final Set<String> RECHARGE_METHODS = Set.of("cash", "card", "wxpay", "alipay");

    private final MemberCardRepository cardRepo;
    private final CardLedgerRepository ledgerRepo;
    private final CustomerRepository customerRepo;
    private final CardFinanceEventPublisher publisher;
    private final AuditRecorder audit;

    public CardLedgerService(MemberCardRepository cardRepo, CardLedgerRepository ledgerRepo,
                             CustomerRepository customerRepo, CardFinanceEventPublisher publisher,
                             AuditRecorder audit) {
        this.cardRepo = cardRepo;
        this.ledgerRepo = ledgerRepo;
        this.customerRepo = customerRepo;
        this.publisher = publisher;
        this.audit = audit;
    }

    /**
     * 会员卡充值（对外收银端点）：行锁找卡 → 校验在用 → 余额加款 → 写 RECHARGE 流水（balance_after 快照）
     * → 审计 → 同事务落资金事件 outbox。充值单号 RC+yyyyMMdd-序号 服务端生成；重放同号 409。
     */
    @Transactional
    public synchronized CardLedger recharge(String cardNo, long amount, String payMethod,
                                            String operator, String storeCode) {
        if (amount <= 0) throw new BadReq("充值金额必须为正（单位：分）");
        if (payMethod == null || !RECHARGE_METHODS.contains(payMethod)) {
            throw new BadReq("充值支付方式非法：仅支持 cash/card/wxpay/alipay（不可用储值余额充值）");
        }
        MemberCard card = cardRepo.findForUpdate(cardNo)
                .orElseThrow(() -> new NotFound("会员卡不存在: " + cardNo));
        if (!"在用".equals(card.getStatus())) {
            throw new BadReq("卡状态非「在用」（当前：" + card.getStatus() + "），不可充值");
        }

        String rcNo = nextRechargeNo();
        long after = (card.getBalance() == null ? 0L : card.getBalance()) + amount;
        card.setBalance(after);
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(card.getCustomerId());
        l.setChangeType("RECHARGE");
        l.setAmount(amount);
        l.setBalanceAfter(after);
        l.setBizRef(rcNo);
        l.setOperator(operator);
        l.setStoreCode(storeCode != null ? storeCode : card.getStoreCode());
        CardLedger saved = ledgerRepo.save(l);

        String customerName = customerRepo.findById(card.getCustomerId())
                .map(Customer::getName).orElse(card.getCustomerId());
        audit.record("CARD", rcNo, actor(operator), "RECHARGE",
                "会员卡充值 " + rcNo + "：卡 " + cardNo + "（" + customerName + "）充值 " + yuan(amount)
                        + " 元（" + payMethod + "），余额 " + yuan(after) + " 元");
        publisher.emitRecharge(rcNo, cardNo, amount, payMethod, l.getStoreCode(), customerName);
        return saved;
    }

    /**
     * 储值余额消费扣款（txn 内部端点，X-Internal-Token 系统身份）：行锁找卡 → 校验在用 →
     * 余额不足 422（中文，含当前余额）→ 余额扣款 → 写 CONSUME 负额流水（bizRef=订单号）。
     * 同订单号重放幂等返回既有流水（网络重试不双扣）。
     */
    @Transactional
    public CardLedger consume(String cardNo, String customerId, long amount, String orderNo) {
        if (orderNo == null || orderNo.isBlank()) throw new BadReq("订单号不能为空");
        if (amount <= 0) throw new BadReq("扣款金额必须为正（单位：分）");
        Optional<CardLedger> replay = ledgerRepo.findFirstByBizRef(orderNo);
        if (replay.isPresent()) {
            CardLedger r = replay.get();
            if ("CONSUME".equals(r.getChangeType()) && cardNo.equals(r.getCardNo())) {
                return r;
            }
            throw new Conflict("订单号 " + orderNo + " 已存在其他卡流水，拒绝重复扣款");
        }
        MemberCard card = cardRepo.findForUpdate(cardNo)
                .orElseThrow(() -> new NotFound("会员卡不存在: " + cardNo));
        if (customerId != null && !customerId.isBlank() && !customerId.equals(card.getCustomerId())) {
            throw new BadReq("卡号与客户不匹配：卡 " + cardNo + " 不属于客户 " + customerId);
        }
        if (!"在用".equals(card.getStatus())) {
            throw new BadReq("卡状态非「在用」（当前：" + card.getStatus() + "），不可扣款");
        }
        long before = card.getBalance() == null ? 0L : card.getBalance();
        if (before < amount) {
            throw new Unprocessable("储值余额不足，当前余额 " + yuan(before) + " 元，本次需扣 " + yuan(amount) + " 元");
        }
        long after = before - amount;
        card.setBalance(after);
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(card.getCustomerId());
        l.setChangeType("CONSUME");
        l.setAmount(-amount);
        l.setBalanceAfter(after);
        l.setBizRef(orderNo);
        l.setOrderNo(orderNo);
        l.setOperator("system");
        l.setStoreCode(card.getStoreCode());
        CardLedger saved = ledgerRepo.save(l);

        audit.record("CARD", orderNo, "system", "CONSUME",
                "储值消费扣款：卡 " + cardNo + " 订单 " + orderNo + " 扣款 " + yuan(amount)
                        + " 元，余额 " + yuan(after) + " 元");
        return saved;
    }

    /**
     * 订单退款回加储值（B4.1，txn 退款终审 RF 后内部回调）：经原订单 CONSUME 扣款流水反查原扣款卡 →
     * 行锁找卡 → 校验在用（已退卡拒绝回加，转人工）→ 余额加回 → 写 REFUND <b>正额</b>流水
     * （bizRef=退款单号 RF…、order_no=订单号，区别于退卡清零的 REFUND 负额）。
     *
     * <p>幂等：同退款单号重放返回既有流水（终审重试不双加）；防超退：按「卡 + 订单」汇总历史回加，
     * 累计回加 + 本次 ≤ 原 CONSUME 扣款额，超额中文拒绝。原订单无储值扣款流水拒绝回加。
     * 资金分录（RF-DEPOSIT/IN 冲回预收）由 txn 域 outbox 投递，本域只动卡台账。
     */
    @Transactional
    public CardLedger refundForOrder(String refundNo, String orderNo, long amount) {
        if (refundNo == null || refundNo.isBlank()) throw new BadReq("退款单号不能为空");
        if (orderNo == null || orderNo.isBlank()) throw new BadReq("订单号不能为空");
        if (amount <= 0) throw new BadReq("退款回加金额必须为正（单位：分）");

        Optional<CardLedger> replay = ledgerRepo.findFirstByBizRefAndChangeType(refundNo, "REFUND");
        if (replay.isPresent()) {
            CardLedger r = replay.get();
            if (r.getAmount() != null && r.getAmount() > 0) {
                return r;
            }
            throw new Conflict("退款单号 " + refundNo + " 已存在退卡清零流水，非订单退款回加场景，拒绝重复处理");
        }

        CardLedger consume = ledgerRepo.findFirstByBizRefAndChangeType(orderNo, "CONSUME")
                .orElseThrow(() -> new BadReq("订单 " + orderNo + " 无储值余额扣款流水，无可回加的储值卡"));
        String cardNo = consume.getCardNo();
        long consumed = consume.getAmount() == null ? 0L : -consume.getAmount();
        long already = ledgerRepo.sumRefundedByOrder(cardNo, orderNo);
        if (already < 0) already = 0L;
        if (already + amount > consumed) {
            throw new BadReq("订单 " + orderNo + " 储值退款回加超额：原扣款 " + yuan(consumed) + " 元，已回加 "
                    + yuan(already) + " 元，本次申请 " + yuan(amount) + " 元，超出部分请走人工核对");
        }

        MemberCard card = cardRepo.findForUpdate(cardNo)
                .orElseThrow(() -> new NotFound("会员卡不存在: " + cardNo));
        if (!"在用".equals(card.getStatus())) {
            throw new Unprocessable("卡 " + cardNo + " 状态为「" + card.getStatus()
                    + "」，订单退款不可自动回加储值，请转人工处理（现金/转账退还客户 " + yuan(amount) + " 元）");
        }
        long before = card.getBalance() == null ? 0L : card.getBalance();
        long after = before + amount;
        card.setBalance(after);
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(card.getCustomerId());
        l.setChangeType("REFUND");
        l.setAmount(amount);
        l.setBalanceAfter(after);
        l.setBizRef(refundNo);
        l.setOrderNo(orderNo);
        l.setOperator("system");
        l.setStoreCode(card.getStoreCode());
        CardLedger saved = ledgerRepo.save(l);

        audit.record("CARD", refundNo, "system", "REFUND",
                "订单退款回加储值：退款单 " + refundNo + " 订单 " + orderNo + " 回加卡 " + cardNo
                        + " " + yuan(amount) + " 元，余额 " + yuan(after) + " 元");
        return saved;
    }

    /**
     * 退卡终审联动（txn 退卡 CC 终审后内部回调）：行锁找卡 → 状态置「已退卡」→ 余额清零 →
     * 写 REFUND 负额流水（amount=-旧余额、balance_after=0，bizRef=退卡单号）。
     * 已退卡/同退卡单号重放幂等返回（终审重试不重复清零）。资金分录由 txn 域 outbox 投递。
     */
    @Transactional
    public CardLedger refund(String cardNo, String cancelNo) {
        if (cancelNo == null || cancelNo.isBlank()) throw new BadReq("退卡单号不能为空");
        Optional<CardLedger> replay = ledgerRepo.findFirstByBizRef(cancelNo);
        if (replay.isPresent()) {
            CardLedger r = replay.get();
            if ("REFUND".equals(r.getChangeType()) && cardNo.equals(r.getCardNo())) {
                return r;
            }
            throw new Conflict("退卡单号 " + cancelNo + " 已存在其他卡流水，拒绝重复退卡");
        }
        MemberCard card = cardRepo.findForUpdate(cardNo)
                .orElseThrow(() -> new NotFound("会员卡不存在: " + cardNo));
        long before = card.getBalance() == null ? 0L : card.getBalance();

        card.setStatus("已退卡");
        card.setBalance(0L);
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(card.getCustomerId());
        l.setChangeType("REFUND");
        l.setAmount(-before);
        l.setBalanceAfter(0L);
        l.setBizRef(cancelNo);
        l.setOperator("system");
        l.setStoreCode(card.getStoreCode());
        CardLedger saved = ledgerRepo.save(l);

        audit.record("CARD", cancelNo, "system", "REFUND",
                "退卡终审清零：卡 " + cardNo + " 退卡单 " + cancelNo + " 退回余额 " + yuan(before)
                        + " 元，卡状态置「已退卡」");
        return saved;
    }

    /** 卡储值流水（按账龄正序），卡详情「储值流水」读模型。 */
    public List<CardLedger> listLedger(String cardNo) {
        if (cardRepo.findById(cardNo).isEmpty()) {
            throw new NotFound("会员卡不存在: " + cardNo);
        }
        return ledgerRepo.findByCardNoOrderByLedgerIdAsc(cardNo);
    }

    /** 生成下一个充值单号：RC+yyyyMMdd-6 位序号，基于库内当日最大号递增（synchronized 防并发重号，与 txn PM 单号同口径）。 */
    private synchronized String nextRechargeNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long seq = ledgerRepo.maxSeqOfDay("RC" + day + "-%") + 1;
        return "RC" + day + "-" + String.format("%06d", seq);
    }

    private static String actor(String operator) {
        String a = DataScope.currentActor();
        if (a != null && !a.isBlank()) return a;
        return (operator == null || operator.isBlank()) ? "system" : operator;
    }

    /** 分 → 元文案（余额/金额提示用，保留两位小数）。 */
    static String yuan(long cents) {
        return String.format("%.2f", cents / 100.0);
    }

    /** 业务异常 → HTTP 状态码映射（由 GlobalExceptionHandler 处理）。 */
    public static class NotFound extends RuntimeException { public NotFound(String m) { super(m); } }
    public static class BadReq extends RuntimeException { public BadReq(String m) { super(m); } }
    public static class Conflict extends RuntimeException { public Conflict(String m) { super(m); } }
    public static class Unprocessable extends RuntimeException { public Unprocessable(String m) { super(m); } }
}
