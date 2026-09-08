package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>资金联动：充值成功同事务经 {@link CardFinanceEventPublisher} 落 outbox（RF-DEPOSIT/IN，仅本金部分，
 * 赠金属营销赠送不进资金分录）；消费/退款回加/退卡的资金分录由 txn 域 outbox 投递，本域只动卡台账。
 *
 * <p>B18 赠金：充值可带赠送金额（giftAmount），消费/划扣扣减<b>先赠金（gift_balance）后本金（balance）</b>，
 * 赠金同台账留痕（card_ledger.gift_amount/gift_after 两列），对账恒等式 Σ gift_amount = gift_balance。
 * B18 退卡冻结：CC 单发起即 freeze（在用→退卡中，冻结期非「在用」校验自然拦截消费/划扣/充值/退款回加），
 * 驳回 unfreeze（退卡中→在用），终审 refund 置「已退卡」本金与赠金一并清零。
 */
@Service
public class CardLedgerService {

    /** 充值支付方式白名单（充值不能用储值余额 balance，防自我充值套现）。 */
    private static final Set<String> RECHARGE_METHODS = Set.of("cash", "card", "wxpay", "alipay");

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
     * 会员卡充值（对外收银端点）：行锁找卡 → 校验在用 → 本金/赠金分别加款 → 写 RECHARGE 流水
     * （balance_after/gift_after 双快照）→ 审计 → 同事务落资金事件 outbox（仅本金部分，赠金不进资金分录）。
     * 充值单号 RC+yyyyMMdd-序号 服务端生成；重放同号 409。giftAmount 为赠送金额（分，≥0，可省=0）。
     */
    @Transactional
    public synchronized CardLedger recharge(String cardNo, long amount, long giftAmount, String payMethod,
                                            String operator, String storeCode) {
        if (amount <= 0) throw new BadReq("充值金额必须为正（单位：分）");
        if (giftAmount < 0) throw new BadReq("赠送金额不能为负（单位：分）");
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
        long giftBefore = card.getGiftBalance() == null ? 0L : card.getGiftBalance();
        long giftAfter = giftBefore + giftAmount;
        card.setBalance(after);
        card.setGiftBalance(giftAfter);
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(card.getCustomerId());
        l.setChangeType("RECHARGE");
        l.setAmount(amount);
        l.setBalanceAfter(after);
        l.setGiftAmount(giftAmount);
        l.setGiftAfter(giftAfter);
        l.setBizRef(rcNo);
        l.setOperator(operator);
        l.setStoreCode(storeCode != null ? storeCode : card.getStoreCode());
        CardLedger saved = ledgerRepo.save(l);

        String customerName = customerRepo.findById(card.getCustomerId())
                .map(Customer::getName).orElse(card.getCustomerId());
        audit.record("CARD", rcNo, actor(operator), "RECHARGE",
                json(rechargeAudit(cardNo, rcNo, customerName, amount, giftAmount, payMethod, after, giftAfter,
                        "会员卡充值 " + yuan(amount) + " 元（" + payMethod + "）"
                                + (giftAmount > 0 ? "，赠送 " + yuan(giftAmount) + " 元" : "")
                                + "，本金余额 " + yuan(after) + " 元"
                                + (giftAfter > 0 ? "、赠金余额 " + yuan(giftAfter) + " 元" : ""))));
        publisher.emitRecharge(rcNo, cardNo, amount, payMethod, l.getStoreCode(), customerName);
        return saved;
    }

    /**
     * 售卡开卡（B16，txn 售卡订单收款收齐后内部回调，X-Internal-Token 系统身份）：客户域为开卡权威，
     * 实例化 member_card（total_times=remain_times=模板次数、balance=售价分、status=在用、
     * product_code/card_type/expires_at/sale_no/gift_balance 溯源字段落库），并写首笔 RECHARGE <b>正额</b>
     * 流水（biz_ref=order_no 售卡订单号、balance_after=售价，保持 Σ card_ledger.amount = balance 恒等式）。
     *
     * <p>写接口四件套：①校验（订单号/客户/卡名/次数/售价中文拒绝，客户不存在 404）；
     * ②幂等（以售卡订单号 sale_no 反查，已开出卡直接返回既有卡号，收款回调网络重试不重复开卡）；
     * ③全审计（CARD/ISSUE，payload 合法 JSON）；④并发安全（synchronized 生成 MC+yyyyMMdd-6 位卡号，
     * 库内当日最大号递增，与 RC 单号同口径）。资金分录（RF-DEPOSIT/IN 预收）由 txn 域 outbox 投递，
     * 本域只动卡台账与卡实例。首笔流水复用 RECHARGE 类型，不动 card_ledger 系统表 CHECK 约束。
     */
    @Transactional
    public synchronized MemberCard issue(String orderNo, String customerId, String storeCode,
                                         String productCode, String cardType, String cardItem,
                                         int totalTimes, int validityDays, long priceFen,
                                         long giftBalance, String operator) {
        if (orderNo == null || orderNo.isBlank()) throw new BadReq("售卡订单号不能为空");
        if (customerId == null || customerId.isBlank()) throw new BadReq("客户编号不能为空");
        if (cardItem == null || cardItem.isBlank()) throw new BadReq("卡项名称不能为空");
        if (totalTimes <= 0) throw new BadReq("卡总次数必须为正（储值卡按模板 1 次）");
        if (validityDays < 0) throw new BadReq("有效期天数不能为负");
        if (priceFen <= 0) throw new BadReq("售卡售价必须为正（单位：分）");
        if (giftBalance < 0) throw new BadReq("赠送金额不能为负（单位：分）");

        Optional<MemberCard> replay = cardRepo.findFirstBySaleNo(orderNo);
        if (replay.isPresent()) {
            return replay.get();
        }
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new NotFound("客户不存在: " + customerId));

        String cardNo = nextCardNo();
        OffsetDateTime now = OffsetDateTime.now();

        MemberCard card = new MemberCard();
        card.setCardNo(cardNo);
        card.setCustomerId(customerId);
        card.setCardItem(cardItem.trim());
        card.setStoreCode(storeCode == null ? "" : storeCode.trim());
        card.setTotalTimes(totalTimes);
        card.setRemainTimes(totalTimes);
        card.setBalance(priceFen);
        card.setGiftBalance(giftBalance);
        card.setStatus("在用");
        card.setProductCode(productCode == null || productCode.isBlank() ? null : productCode.trim());
        card.setCardType(cardType == null || cardType.isBlank() ? null : cardType.trim());
        card.setSaleNo(orderNo);
        card.setExpiresAt(validityDays > 0 ? now.plusDays(validityDays) : null);
        card.setCreatedAt(now);
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(customerId);
        l.setChangeType("RECHARGE");
        l.setAmount(priceFen);
        l.setBalanceAfter(priceFen);
        l.setGiftAmount(giftBalance);
        l.setGiftAfter(giftBalance);
        l.setBizRef(orderNo);
        l.setOrderNo(orderNo);
        l.setOperator("system");
        l.setStoreCode(card.getStoreCode());
        ledgerRepo.save(l);

        String customerName = customer.getName() == null ? customerId : customer.getName();
        audit.record("CARD", orderNo, "system", "ISSUE",
                json(issuePayload(cardNo, orderNo, customerId, customerName, productCode, cardType,
                        cardItem, totalTimes, validityDays, priceFen, giftBalance,
                        "售卡开卡：售出「" + cardItem.trim() + "」" + yuan(priceFen) + " 元，开卡 " + cardNo
                                + "（" + totalTimes + " 次" + (giftBalance > 0 ? "、赠金 " + yuan(giftBalance) + " 元" : "")
                                + "），首笔充值 " + yuan(priceFen) + " 元")));
        return card;
    }

    /**
     * 储值余额消费扣款（txn 内部端点，X-Internal-Token 系统身份）：行锁找卡 → 校验在用 →
     * 扣减顺序<b>先赠金后本金</b>（giftUsed=min(赠金余额, 扣款额)，剩余扣本金；本金+赠金合计不足 422 中文）
     * → 写 CONSUME 负额流水（amount=-本金扣减、gift_amount=-赠金扣减，balance_after/gift_after 双快照，
     * bizRef=订单号）。同订单号重放幂等返回既有流水（网络重试不双扣）。
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
        long giftBefore = card.getGiftBalance() == null ? 0L : card.getGiftBalance();
        if (before + giftBefore < amount) {
            throw new Unprocessable("储值余额不足：本金 " + yuan(before) + " 元、赠金 " + yuan(giftBefore)
                    + " 元，本次需扣 " + yuan(amount) + " 元");
        }
        long giftUsed = Math.min(giftBefore, amount);
        long principalUsed = amount - giftUsed;
        long after = before - principalUsed;
        long giftAfter = giftBefore - giftUsed;
        card.setBalance(after);
        card.setGiftBalance(giftAfter);
        // 扣尽状态流转：本金与赠金均清零且卡已无剩余可用（储值卡无次数概念 total_times 占位 1；疗程卡须剩余次数也为 0）→ 已用完。
        // 疗程卡余额为 0 但仍有剩余次数时，后续可走纯扣次（0 额）划扣，不得提前置「已用完」。
        boolean depleted = after == 0 && giftAfter == 0
                && ("CARD".equals(card.getCardType()) || (card.getRemainTimes() == null || card.getRemainTimes() == 0));
        if (depleted) {
            card.setStatus("已用完");
        }
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(card.getCustomerId());
        l.setChangeType("CONSUME");
        l.setAmount(-principalUsed);
        l.setBalanceAfter(after);
        l.setGiftAmount(-giftUsed);
        l.setGiftAfter(giftAfter);
        l.setBizRef(orderNo);
        l.setOrderNo(orderNo);
        l.setOperator("system");
        l.setStoreCode(card.getStoreCode());
        CardLedger saved = ledgerRepo.save(l);

        audit.record("CARD", orderNo, "system", "CONSUME",
                json(consumeAudit(cardNo, orderNo, amount, principalUsed, giftUsed, after, giftAfter,
                        depleted, false,
                        "储值消费扣款 " + yuan(amount) + " 元（赠金 " + yuan(giftUsed) + " 元、本金 "
                                + yuan(principalUsed) + " 元），本金余额 " + yuan(after) + " 元、赠金余额 "
                                + yuan(giftAfter) + " 元" + (depleted ? "，卡余额扣尽已置「已用完」" : ""))));
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
                json(Map.of("cardNo", cardNo, "refundNo", refundNo, "orderNo", orderNo,
                        "amount", amount, "balanceAfter", after, "kind", "ORDER_REFUND",
                        "summary", "订单退款回加储值 " + yuan(amount) + " 元，余额 " + yuan(after) + " 元")));
        return saved;
    }

    /**
     * 退卡终审联动（txn 退卡 CC 终审后内部回调）：行锁找卡 → 状态置「已退卡」→ 本金与赠金一并清零 →
     * 写 REFUND 负额流水（amount=-旧本金、gift_amount=-旧赠金、balance_after/gift_after=0，bizRef=退卡单号）。
     * 已退卡/同退卡单号 REFUND 重放幂等返回（终审重试不重复清零；冻结/解冻 ADJUST 行 bizRef 带 -F/-U 后缀，
     * 不与本方法重放冲突）。资金分录由 txn 域 outbox 投递。
     */
    @Transactional
    public CardLedger refund(String cardNo, String cancelNo) {
        if (cancelNo == null || cancelNo.isBlank()) throw new BadReq("退卡单号不能为空");
        Optional<CardLedger> replay = ledgerRepo.findFirstByBizRefAndChangeType(cancelNo, "REFUND");
        if (replay.isPresent()) {
            CardLedger r = replay.get();
            if (cardNo.equals(r.getCardNo())) {
                return r;
            }
            throw new Conflict("退卡单号 " + cancelNo + " 已存在其他卡流水，拒绝重复退卡");
        }
        MemberCard card = cardRepo.findForUpdate(cardNo)
                .orElseThrow(() -> new NotFound("会员卡不存在: " + cardNo));
        long before = card.getBalance() == null ? 0L : card.getBalance();
        long giftBefore = card.getGiftBalance() == null ? 0L : card.getGiftBalance();

        card.setStatus("已退卡");
        card.setBalance(0L);
        card.setGiftBalance(0L);
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(card.getCustomerId());
        l.setChangeType("REFUND");
        l.setAmount(-before);
        l.setBalanceAfter(0L);
        l.setGiftAmount(-giftBefore);
        l.setGiftAfter(0L);
        l.setBizRef(cancelNo);
        l.setOperator("system");
        l.setStoreCode(card.getStoreCode());
        CardLedger saved = ledgerRepo.save(l);

        audit.record("CARD", cancelNo, "system", "REFUND",
                json(refundAudit(cardNo, cancelNo, before, giftBefore,
                        "退卡终审清零：退回本金 " + yuan(before) + " 元、核销赠金 " + yuan(giftBefore)
                                + " 元，卡置「已退卡」")));
        return saved;
    }

    /**
     * 退卡发起冻结（B18，txn CC 单创建后内部回调）：行锁找卡 → 在用→退卡中 → 写 ADJUST 流水
     * （amount=0、balance_after=当前本金快照，gift 两列留 NULL，bizRef=退卡单号-F，仅状态留痕不动钱）。
     * 冻结期 recharge/consume/writeoff/refundForOrder 均有「非在用」中文校验，自然拦截冻结期动账。
     * 幂等：同单号 -F 的 ADJUST 重放返回既有行；卡已「退卡中」按成功处理；已「已退卡」409；其他状态 400。
     */
    @Transactional
    public CardLedger freeze(String cardNo, String cancelNo) {
        if (cancelNo == null || cancelNo.isBlank()) throw new BadReq("退卡单号不能为空");
        String ref = cancelNo + "-F";
        Optional<CardLedger> replay = ledgerRepo.findFirstByBizRefAndChangeType(ref, "ADJUST");
        if (replay.isPresent()) {
            return replay.get();
        }
        MemberCard card = cardRepo.findForUpdate(cardNo)
                .orElseThrow(() -> new NotFound("会员卡不存在: " + cardNo));
        if ("已退卡".equals(card.getStatus())) {
            throw new Conflict("卡 " + cardNo + " 已退卡，不可冻结");
        }
        if (!"在用".equals(card.getStatus()) && !"退卡中".equals(card.getStatus())) {
            throw new BadReq("卡状态为「" + card.getStatus() + "」，不可发起退卡冻结");
        }
        long balance = card.getBalance() == null ? 0L : card.getBalance();
        long gift = card.getGiftBalance() == null ? 0L : card.getGiftBalance();

        card.setStatus("退卡中");
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(card.getCustomerId());
        l.setChangeType("ADJUST");
        l.setAmount(0L);
        l.setBalanceAfter(balance);
        l.setBizRef(ref);
        l.setOperator("system");
        l.setStoreCode(card.getStoreCode());
        CardLedger saved = ledgerRepo.save(l);

        audit.record("CARD", ref, "system", "FREEZE",
                json(adjustAudit(cardNo, cancelNo, "FREEZE", balance, gift,
                        "退卡发起冻结：卡置「退卡中」，冻结期不可消费/划扣/充值/退款回加，冻结时本金 "
                                + yuan(balance) + " 元、赠金 " + yuan(gift) + " 元")));
        return saved;
    }

    /**
     * 退卡驳回解冻（B18，txn CC 单驳回后内部回调）：行锁找卡 → 退卡中→在用 → 写 ADJUST 流水
     * （amount=0、balance_after=当前本金快照，gift 两列留 NULL，bizRef=退卡单号-U）。
     * 幂等：同单号 -U 的 ADJUST 重放返回既有行；卡已「在用」按成功处理；已「已退卡」409（终审已清卡不可逆转）；
     * 其他状态 400。
     */
    @Transactional
    public CardLedger unfreeze(String cardNo, String cancelNo) {
        if (cancelNo == null || cancelNo.isBlank()) throw new BadReq("退卡单号不能为空");
        String ref = cancelNo + "-U";
        Optional<CardLedger> replay = ledgerRepo.findFirstByBizRefAndChangeType(ref, "ADJUST");
        if (replay.isPresent()) {
            return replay.get();
        }
        MemberCard card = cardRepo.findForUpdate(cardNo)
                .orElseThrow(() -> new NotFound("会员卡不存在: " + cardNo));
        if ("已退卡".equals(card.getStatus())) {
            throw new Conflict("卡 " + cardNo + " 已退卡终审，不可解冻");
        }
        if (!"退卡中".equals(card.getStatus()) && !"在用".equals(card.getStatus())) {
            throw new BadReq("卡状态为「" + card.getStatus() + "」，不可解冻");
        }
        long balance = card.getBalance() == null ? 0L : card.getBalance();
        long gift = card.getGiftBalance() == null ? 0L : card.getGiftBalance();

        card.setStatus("在用");
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(card.getCustomerId());
        l.setChangeType("ADJUST");
        l.setAmount(0L);
        l.setBalanceAfter(balance);
        l.setBizRef(ref);
        l.setOperator("system");
        l.setStoreCode(card.getStoreCode());
        CardLedger saved = ledgerRepo.save(l);

        audit.record("CARD", ref, "system", "UNFREEZE",
                json(adjustAudit(cardNo, cancelNo, "UNFREEZE", balance, gift,
                        "退卡驳回解冻：卡恢复「在用」，解冻时本金 " + yuan(balance) + " 元、赠金 "
                                + yuan(gift) + " 元")));
        return saved;
    }

    /**
     * 疗程卡扣次划扣联动（B6 G1，txn 划扣两条路径双签后内部回调）：customer 权威卡台账为唯一动账方，
     * 行锁找卡 → 校验在用 → 剩余次数 ≥ 扣次（不足 422 中文）→ 扣额 ≤ 余额（不足 422 中文）→
     * member_card 扣 remainTimes/balance、次数扣尽置「已用完」→ 始终写 CONSUME 流水
     * （bizRef=划扣单号 WO…、order_no 留空——划扣非订单收款，不参与订单退款回加防超退口径）。
     *
     * <p>纯扣次（amount=0，疗程卡余额为 0）同样写一行 amount=0 的 CONSUME 流水：0 额行不影响
     * Σ amount = balance 恒等式，且为 WO 单号提供幂等锚点（否则纯扣次重放无流水可查，网络重试/
     * 回填重跑会双扣次数）。幂等：同划扣单号重放返回既有流水，网络重试不双扣。
     * 资金分录（RF-DEPOSIT/OUT + RF-REVENUE/IN）由 txn 域 outbox 投递，本域只动卡台账。
     *
     * <p>回填模式（backfill=true，B6 存量修复）：B6 前历史划扣在共表部署下已由 txn 本地扣卡
     * 更新 member_card（次数/余额当时已扣），仅缺 card_ledger 流水破坏 Σ amount = balance 恒等式。
     * 故回填<b>只补记 CONSUME 流水、不重复扣减 remain_times/balance</b>，不校验卡状态（已用完/已退卡
     * 同样补记），balance_after 取回填执行时卡余额（operator=system-backfill 可识别）；卡不存在 404、
     * WO 号冲突 409 由 txn 侧收集为差异清单转人工，不自动改数。
     */
    @Transactional
    public CardLedger writeoff(String cardNo, String writeoffId, int timesUsed, long amount,
                               String storeCode, boolean backfill) {
        if (writeoffId == null || writeoffId.isBlank()) throw new BadReq("划扣单号不能为空");
        if (timesUsed < 1) throw new BadReq("划扣次数必须 ≥ 1");
        if (amount < 0) throw new BadReq("划扣金额不能为负（单位：分）");
        Optional<CardLedger> replay = ledgerRepo.findFirstByBizRef(writeoffId);
        if (replay.isPresent()) {
            CardLedger r = replay.get();
            if ("CONSUME".equals(r.getChangeType()) && cardNo.equals(r.getCardNo())) {
                return r;
            }
            throw new Conflict("划扣单号 " + writeoffId + " 已存在其他卡流水，拒绝重复划扣");
        }
        MemberCard card = cardRepo.findForUpdate(cardNo)
                .orElseThrow(() -> new NotFound("会员卡不存在: " + cardNo));

        if (backfill) {
            long currentBalance = card.getBalance() == null ? 0L : card.getBalance();
            CardLedger l = new CardLedger();
            l.setCardNo(cardNo);
            l.setCustomerId(card.getCustomerId());
            l.setChangeType("CONSUME");
            l.setAmount(-amount);
            l.setBalanceAfter(currentBalance);
            l.setBizRef(writeoffId);
            l.setOperator("system-backfill");
            l.setStoreCode(storeCode != null && !storeCode.isBlank() ? storeCode : card.getStoreCode());
            CardLedger saved = ledgerRepo.save(l);

            audit.record("CARD", writeoffId, "system", "WRITEOFF_BACKFILL",
                    json(backfillPayload(cardNo, writeoffId, timesUsed, amount, currentBalance,
                            "存量划扣回填补记流水：卡次数/余额已于历史划扣时扣减，本次不重复扣卡，回填时余额 "
                                    + yuan(currentBalance) + " 元")));
            return saved;
        }

        if (!"在用".equals(card.getStatus())) {
            throw new BadReq("卡状态非「在用」（当前：" + card.getStatus() + "），不可划扣");
        }
        int remain = card.getRemainTimes() == null ? 0 : card.getRemainTimes();
        if (remain < timesUsed) {
            throw new Unprocessable("卡剩余次数不足：当前剩余 " + remain + " 次，本次需扣 " + timesUsed + " 次");
        }
        long before = card.getBalance() == null ? 0L : card.getBalance();
        long giftBefore = card.getGiftBalance() == null ? 0L : card.getGiftBalance();
        if (before + giftBefore < amount) {
            throw new Unprocessable("卡余额不足：本金 " + yuan(before) + " 元、赠金 " + yuan(giftBefore)
                    + " 元，本次需扣 " + yuan(amount) + " 元");
        }
        long giftUsed = Math.min(giftBefore, amount);
        long principalUsed = amount - giftUsed;
        long after = before - principalUsed;
        long giftAfter = giftBefore - giftUsed;

        card.setRemainTimes(remain - timesUsed);
        card.setBalance(after);
        card.setGiftBalance(giftAfter);
        if (card.getRemainTimes() == 0) {
            card.setStatus("已用完");
        }
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(card.getCustomerId());
        l.setChangeType("CONSUME");
        l.setAmount(-principalUsed);
        l.setBalanceAfter(after);
        l.setGiftAmount(-giftUsed);
        l.setGiftAfter(giftAfter);
        l.setBizRef(writeoffId);
        l.setOperator("system");
        l.setStoreCode(storeCode != null && !storeCode.isBlank() ? storeCode : card.getStoreCode());
        CardLedger saved = ledgerRepo.save(l);

        audit.record("CARD", writeoffId, "system", "WRITEOFF",
                json(writeoffPayload(cardNo, writeoffId, timesUsed, amount, principalUsed, giftUsed,
                        card.getRemainTimes() == null ? 0 : card.getRemainTimes(), after, giftAfter, false,
                        "疗程卡扣次划扣：扣 " + timesUsed + " 次"
                                + (amount > 0 ? "、扣额 " + yuan(amount) + " 元（赠金 " + yuan(giftUsed)
                                        + " 元、本金 " + yuan(principalUsed) + " 元）" : "（纯扣次）")
                                + "，本金余额 " + yuan(after) + " 元、赠金余额 " + yuan(giftAfter) + " 元")));
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

    /** 生成下一个开卡卡号：MC+yyyyMMdd-6 位序号，基于 member_card 库内当日最大卡号递增（synchronized 防并发重号）。 */
    private synchronized String nextCardNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long seq = cardRepo.maxCardSeqOfDay("MC" + day + "-%") + 1;
        return "MC" + day + "-" + String.format("%06d", seq);
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

    /** 审计 payload 序列化为合法 JSON 字符串（audit_log.payload 为 jsonb 列，散文会被 PG 以 invalid input syntax for type json 拒绝）。 */
    private static String json(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 划扣审计 payload：卡号/划扣单号/扣次/扣额（总额+本金/赠金拆分）/剩余次数与双余额快照/回填标记，动账权威来源 customer。 */
    private static Map<String, Object> writeoffPayload(String cardNo, String writeoffId, int timesUsed,
                                                       long amount, long principalUsed, long giftUsed,
                                                       int remainTimesAfter, long balanceAfter, long giftAfter,
                                                       boolean backfill, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardNo", cardNo);
        m.put("writeoffId", writeoffId);
        m.put("timesUsed", timesUsed);
        m.put("amount", amount);
        m.put("principalUsed", principalUsed);
        m.put("giftUsed", giftUsed);
        m.put("remainTimesAfter", remainTimesAfter);
        m.put("balanceAfter", balanceAfter);
        m.put("giftAfter", giftAfter);
        m.put("backfill", backfill);
        m.put("authority", "customer");
        m.put("summary", summary);
        return m;
    }

    /** 充值审计 payload：卡号/充值单号/本金/赠金/支付方式/双余额快照，动账权威来源 customer。 */
    private static Map<String, Object> rechargeAudit(String cardNo, String rcNo, String customerName,
                                                     long amount, long giftAmount, String payMethod,
                                                     long balanceAfter, long giftAfter, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardNo", cardNo);
        m.put("rechargeNo", rcNo);
        m.put("customer", customerName);
        m.put("amount", amount);
        m.put("giftAmount", giftAmount);
        m.put("payMethod", payMethod);
        m.put("balanceAfter", balanceAfter);
        m.put("giftAfter", giftAfter);
        m.put("authority", "customer");
        m.put("summary", summary);
        return m;
    }

    /** 消费扣款审计 payload：卡号/订单号/扣款总额/本金与赠金拆分/双余额快照/扣尽标记，动账权威来源 customer。 */
    private static Map<String, Object> consumeAudit(String cardNo, String orderNo, long amount,
                                                    long principalUsed, long giftUsed,
                                                    long balanceAfter, long giftAfter,
                                                    boolean depleted, boolean backfill, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardNo", cardNo);
        m.put("orderNo", orderNo);
        m.put("amount", amount);
        m.put("principalUsed", principalUsed);
        m.put("giftUsed", giftUsed);
        m.put("balanceAfter", balanceAfter);
        m.put("giftAfter", giftAfter);
        m.put("depleted", depleted);
        m.put("backfill", backfill);
        m.put("authority", "customer");
        m.put("summary", summary);
        return m;
    }

    /** 退卡清零审计 payload：卡号/退卡单号/本金退回与赠金核销额，动账权威来源 customer。 */
    private static Map<String, Object> refundAudit(String cardNo, String cancelNo, long refundAmount,
                                                   long giftCleared, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardNo", cardNo);
        m.put("cancelNo", cancelNo);
        m.put("refundAmount", refundAmount);
        m.put("giftCleared", giftCleared);
        m.put("balanceAfter", 0L);
        m.put("giftAfter", 0L);
        m.put("kind", "CARD_CANCEL");
        m.put("authority", "customer");
        m.put("summary", summary);
        return m;
    }

    /** 冻结/解冻 ADJUST 审计 payload：仅状态留痕不动钱，记录操作时本金/赠金快照供对账溯源。 */
    private static Map<String, Object> adjustAudit(String cardNo, String cancelNo, String action,
                                                   long balanceAtAction, long giftAtAction, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardNo", cardNo);
        m.put("cancelNo", cancelNo);
        m.put("action", action);
        m.put("balanceAtAction", balanceAtAction);
        m.put("giftAtAction", giftAtAction);
        m.put("kind", "CARD_FREEZE_TOGGLE");
        m.put("authority", "customer");
        m.put("summary", summary);
        return m;
    }

    /** 售卡开卡审计 payload：卡号/售卡订单/客户/模板溯源/次数/有效期/售价与赠金快照，开卡权威来源 customer。 */
    private static Map<String, Object> issuePayload(String cardNo, String orderNo, String customerId,
                                                    String customerName, String productCode, String cardType,
                                                    String cardItem, int totalTimes, int validityDays,
                                                    long priceFen, long giftBalance, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardNo", cardNo);
        m.put("saleNo", orderNo);
        m.put("orderNo", orderNo);
        m.put("customerId", customerId);
        m.put("customer", customerName);
        m.put("productCode", productCode);
        m.put("cardType", cardType);
        m.put("cardItem", cardItem);
        m.put("totalTimes", totalTimes);
        m.put("remainTimes", totalTimes);
        m.put("validityDays", validityDays);
        m.put("priceFen", priceFen);
        m.put("giftBalance", giftBalance);
        m.put("balanceAfter", priceFen);
        m.put("authority", "customer");
        m.put("summary", summary);
        return m;
    }

    /** 存量回填补记审计 payload：历史划扣已扣卡本次不重复扣减，记录回填时余额快照以与正常划扣区分。 */
    private static Map<String, Object> backfillPayload(String cardNo, String writeoffId, int timesUsed,
                                                       long amount, long balanceAtBackfill, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardNo", cardNo);
        m.put("writeoffId", writeoffId);
        m.put("timesUsed", timesUsed);
        m.put("amount", amount);
        m.put("balanceAtBackfill", balanceAtBackfill);
        m.put("backfill", true);
        m.put("authority", "customer");
        m.put("summary", summary);
        return m;
    }

    /** 业务异常 → HTTP 状态码映射（由 GlobalExceptionHandler 处理）。 */
    public static class NotFound extends RuntimeException { public NotFound(String m) { super(m); } }
    public static class BadReq extends RuntimeException { public BadReq(String m) { super(m); } }
    public static class Conflict extends RuntimeException { public Conflict(String m) { super(m); } }
    public static class Unprocessable extends RuntimeException { public Unprocessable(String m) { super(m); } }
}
