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
 * <p>资金联动：充值成功同事务经 {@link CardFinanceEventPublisher} 落 outbox（RF-DEPOSIT/IN）；
 * 消费/退款回加/退卡的资金分录由 txn 域 outbox 投递，本域只动卡台账。
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
                json(Map.of("cardNo", cardNo, "customer", customerName, "amount", amount,
                        "payMethod", payMethod, "balanceAfter", after,
                        "summary", "会员卡充值 " + yuan(amount) + " 元（" + payMethod + "），余额 " + yuan(after) + " 元")));
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
        // 扣尽状态流转：余额清零且卡已无剩余可用（储值卡无次数概念 total_times 占位 1；疗程卡须剩余次数也为 0）→ 已用完。
        // 疗程卡余额为 0 但仍有剩余次数时，后续可走纯扣次（0 额）划扣，不得提前置「已用完」。
        boolean depleted = after == 0
                && ("CARD".equals(card.getCardType()) || (card.getRemainTimes() == null || card.getRemainTimes() == 0));
        if (depleted) {
            card.setStatus("已用完");
        }
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
                json(Map.of("cardNo", cardNo, "orderNo", orderNo, "amount", amount,
                        "balanceAfter", after, "authority", "customer",
                        "depleted", depleted,
                        "summary", "储值消费扣款 " + yuan(amount) + " 元，余额 " + yuan(after) + " 元"
                                + (depleted ? "，卡余额扣尽已置「已用完」" : ""))));
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
                json(Map.of("cardNo", cardNo, "cancelNo", cancelNo, "refundAmount", before,
                        "balanceAfter", 0L, "kind", "CARD_CANCEL",
                        "summary", "退卡终审清零退回余额 " + yuan(before) + " 元，卡置「已退卡」")));
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
        if (before < amount) {
            throw new Unprocessable("卡余额不足：当前余额 " + yuan(before) + " 元，本次需扣 " + yuan(amount) + " 元");
        }

        card.setRemainTimes(remain - timesUsed);
        long after = before - amount;
        if (amount > 0) {
            card.setBalance(after);
        }
        if (card.getRemainTimes() == 0) {
            card.setStatus("已用完");
        }
        cardRepo.save(card);

        CardLedger l = new CardLedger();
        l.setCardNo(cardNo);
        l.setCustomerId(card.getCustomerId());
        l.setChangeType("CONSUME");
        l.setAmount(-amount);
        l.setBalanceAfter(after);
        l.setBizRef(writeoffId);
        l.setOperator("system");
        l.setStoreCode(storeCode != null && !storeCode.isBlank() ? storeCode : card.getStoreCode());
        CardLedger saved = ledgerRepo.save(l);

        audit.record("CARD", writeoffId, "system", "WRITEOFF",
                json(writeoffPayload(cardNo, writeoffId, timesUsed, amount,
                        card.getRemainTimes() == null ? 0 : card.getRemainTimes(), after, false,
                        "疗程卡扣次划扣：扣 " + timesUsed + " 次"
                                + (amount > 0 ? "、扣额 " + yuan(amount) + " 元" : "（纯扣次）")
                                + "，余额 " + yuan(after) + " 元")));
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

    /** 划扣审计 payload：卡号/划扣单号/扣次/扣额/剩余次数与余额快照/回填标记，动账权威来源 customer。 */
    private static Map<String, Object> writeoffPayload(String cardNo, String writeoffId, int timesUsed,
                                                       long amount, int remainTimesAfter, long balanceAfter,
                                                       boolean backfill, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardNo", cardNo);
        m.put("writeoffId", writeoffId);
        m.put("timesUsed", timesUsed);
        m.put("amount", amount);
        m.put("remainTimesAfter", remainTimesAfter);
        m.put("balanceAfter", balanceAfter);
        m.put("backfill", backfill);
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
