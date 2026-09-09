package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务间内部端点（非业务页面）：finance 卡余额只读投影 + txn 储值扣款/退卡回写。
 *
 * <p>红线边界：会员卡余额/客户真实姓名属敏感财务字段，仅以系统身份（X-Internal-Token，
 * perms=["*"]）开放；普通登录人无 {@code internal:card-balance} / {@code internal:card-write}
 * 权限 → 403。财务域不直读 member_card / customer 表，交易域不直写卡台账，
 * 由客户域按自身实体语义提供投影/动作，拆库后零改动成立。
 */
@RestController
@RequestMapping("/api/customer/internal")
public class InternalCardController {

    private final MemberCardRepository cardRepo;
    private final CustomerRepository customerRepo;
    private final CardLedgerRepository ledgerRepo;
    private final CardLedgerService ledgerService;

    public InternalCardController(MemberCardRepository cardRepo, CustomerRepository customerRepo,
                                  CardLedgerRepository ledgerRepo, CardLedgerService ledgerService) {
        this.cardRepo = cardRepo;
        this.customerRepo = customerRepo;
        this.ledgerRepo = ledgerRepo;
        this.ledgerService = ledgerService;
    }

    /**
     * 会员卡余额批量投影：GET /api/customer/internal/card-balances?storeCode=SST01。
     * 返回客户域全量（或单店）会员卡，客户姓名已解析；数据权限收敛由 finance-service 二次过滤。
     */
    @GetMapping("/card-balances")
    @RequirePerm("internal:card-balance")
    public List<CardBalanceDTO> cardBalances(
            @RequestParam(value = "storeCode", required = false) String storeCode) {

        List<MemberCard> cards = (storeCode == null || storeCode.isBlank())
                ? cardRepo.findAll()
                : cardRepo.findByStoreCodeOrderByCardNoDesc(storeCode);
        if (cards.isEmpty()) return List.of();

        List<String> customerIds = cards.stream().map(MemberCard::getCustomerId).distinct().toList();
        Map<String, String> nameMap = new HashMap<>();
        for (Customer c : customerRepo.findAllById(customerIds)) {
            if (c.getName() != null) nameMap.put(c.getCustomerId(), c.getName());
        }

        return cards.stream()
                .map(c -> new CardBalanceDTO(c.getCardNo(), c.getCustomerId(),
                        nameMap.getOrDefault(c.getCustomerId(), c.getCustomerId()),
                        c.getCardItem(), c.getStoreCode(), c.getTotalTimes(), c.getRemainTimes(),
                        c.getBalance(), c.getGiftBalance() == null ? 0L : c.getGiftBalance(),
                        c.getStatus(), c.getProductCode(), c.getCardType(),
                        c.getExpiresAt(), c.getCreatedAt()))
                .toList();
    }

    /**
     * 客户目录投影（域⑤ 赠金/外部渠道回传跨域硬校验）：GET /api/customer/internal/customers/{customerId}。
     * 仅回 customerId/姓名/归属门店/状态（不回手机号等敏感字段）；客户不存在 404，由调用方转中文 4xx。
     * 仅系统身份（X-Internal-Token，持 internal:customer-directory）可调，营销域不直读 customer 表。
     */
    @GetMapping("/customers/{customerId}")
    @RequirePerm("internal:customer-directory")
    public CustomerDirectoryDTO customerDirectory(
            @org.springframework.web.bind.annotation.PathVariable("customerId") String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new CardLedgerService.BadReq("客户ID不能为空");
        }
        Customer c = customerRepo.findById(customerId.trim())
                .orElseThrow(() -> new CardLedgerService.NotFound("客户不存在: " + customerId));
        return new CustomerDirectoryDTO(c.getCustomerId(), c.getName(),
                c.getStoreCode() == null ? "" : c.getStoreCode(),
                c.getStatus() == null ? "" : c.getStatus());
    }

    /**
     * 储值余额消费扣款（txn balance 支付实扣）：POST /api/customer/internal/cards/consume。
     * 行锁扣 member_card.balance 并写 card_ledger（CONSUME 负额，bizRef=订单号）；
     * 余额不足 422 中文拦截；同订单号重放幂等返回既有流水（网络重试不双扣）。
     */
    @PostMapping("/cards/consume")
    @RequirePerm("internal:card-write")
    public Map<String, Object> consume(@RequestBody ConsumeCmd cmd) {
        if (cmd == null) throw new CardLedgerService.BadReq("请求体不能为空");
        CardLedger l = ledgerService.consume(cmd.cardNo(), cmd.customerId(),
                cmd.amount() == null ? 0L : cmd.amount(), cmd.orderNo());
        return Map.of(
                "ledgerId", l.getLedgerId(),
                "changeType", l.getChangeType(),
                "balanceAfter", l.getBalanceAfter());
    }

    /**
     * 退卡终审联动（txn 退卡 CC 终审后回调）：POST /api/customer/internal/cards/refund。
     * card_ledger 写 REFUND（负额）、member_card.status=已退卡、余额清零（balance_after=0）；
     * 同退卡单号重放幂等返回。资金分录由 txn 域 outbox 投递，本端点只动卡台账。
     */
    @PostMapping("/cards/refund")
    @RequirePerm("internal:card-write")
    public Map<String, Object> refund(@RequestBody RefundCmd cmd) {
        if (cmd == null) throw new CardLedgerService.BadReq("请求体不能为空");
        CardLedger l = ledgerService.refund(cmd.cardNo(), cmd.cancelNo());
        return Map.of(
                "ledgerId", l.getLedgerId(),
                "changeType", l.getChangeType(),
                "balanceAfter", l.getBalanceAfter(),
                "status", "已退卡");
    }

    /**
     * 退卡发起冻结（B18，txn CC 单创建后同事务回调）：POST /api/customer/internal/cards/freeze。
     * member_card 置「退卡中」并写 ADJUST 流水（amount=0、赠金两列 NULL，bizRef=cancelNo-F）；
     * 冻结期充值/消费/划扣/退款回加由本域「非在用」校验中文拦截。已退卡 409、卡不存在 404 中文透传；
     * 同 cancelNo-F 重放幂等返回既有流水（审批流重试安全）。
     */
    @PostMapping("/cards/freeze")
    @RequirePerm("internal:card-write")
    public Map<String, Object> freeze(@RequestBody FreezeCmd cmd) {
        if (cmd == null) throw new CardLedgerService.BadReq("请求体不能为空");
        CardLedger l = ledgerService.freeze(cmd.cardNo(), cmd.cancelNo());
        return Map.of(
                "ledgerId", l.getLedgerId(),
                "changeType", l.getChangeType(),
                "status", "退卡中");
    }

    /**
     * 退卡驳回解冻（B18，txn CC 单驳回后同事务回调）：POST /api/customer/internal/cards/unfreeze。
     * member_card 置回「在用」并写 ADJUST 流水（bizRef=cancelNo-U）；已终审「已退卡」409 中文透传（不可逆转）；
     * 同 cancelNo-U 重放幂等返回既有流水。
     */
    @PostMapping("/cards/unfreeze")
    @RequirePerm("internal:card-write")
    public Map<String, Object> unfreeze(@RequestBody FreezeCmd cmd) {
        if (cmd == null) throw new CardLedgerService.BadReq("请求体不能为空");
        CardLedger l = ledgerService.unfreeze(cmd.cardNo(), cmd.cancelNo());
        return Map.of(
                "ledgerId", l.getLedgerId(),
                "changeType", l.getChangeType(),
                "status", "在用");
    }

    /**
     * 订单退款回加储值（B4.1，txn 退款终审 RF 后回调）：POST /api/customer/internal/cards/refund-order。
     * 经原订单 CONSUME 流水反查扣款卡，card_ledger 写 REFUND <b>正额</b>（bizRef=退款单号、order_no=订单号）、
     * member_card.balance 加回；累计回加 ≤ 原扣款（防超退）；同退款单号重放幂等返回；卡已退卡 422 中文拦截。
     */
    @PostMapping("/cards/refund-order")
    @RequirePerm("internal:card-write")
    public Map<String, Object> refundOrder(@RequestBody RefundOrderCmd cmd) {
        if (cmd == null) throw new CardLedgerService.BadReq("请求体不能为空");
        CardLedger l = ledgerService.refundForOrder(cmd.refundNo(), cmd.orderNo(),
                cmd.amount() == null ? 0L : cmd.amount());
        return Map.of(
                "ledgerId", l.getLedgerId(),
                "changeType", l.getChangeType(),
                "cardNo", l.getCardNo(),
                "balanceAfter", l.getBalanceAfter());
    }

    /**
     * 疗程卡扣次划扣联动（B6 G1，txn 划扣双签后回调）：POST /api/customer/internal/cards/writeoff。
     * customer 权威卡台账唯一动账方：行锁扣 member_card.remain_times（amount&gt;0 同时扣 balance）、
     * 次数扣尽置「已用完」，写 CONSUME 流水（bizRef=划扣单号 WO…，纯扣次写 0 额流水作幂等锚点）；
     * 卡不存在 404 / 非在用 400 / 次数或余额不足 422，均中文透传；同 WO 单号重放幂等返回既有流水。
     * backfill=true 为存量回填：终态卡 422 拒绝不自动改数，由 txn 侧记差异清单转人工。
     */
    @PostMapping("/cards/writeoff")
    @RequirePerm("internal:card-write")
    public Map<String, Object> writeoff(@RequestBody WriteoffCmd cmd) {
        if (cmd == null) throw new CardLedgerService.BadReq("请求体不能为空");
        CardLedger l = ledgerService.writeoff(cmd.cardNo(), cmd.writeoffId(),
                cmd.timesUsed() == null ? 0 : cmd.timesUsed(),
                cmd.amount() == null ? 0L : cmd.amount(),
                cmd.storeCode(), Boolean.TRUE.equals(cmd.backfill()));
        return Map.of(
                "ledgerId", l.getLedgerId(),
                "changeType", l.getChangeType(),
                "balanceAfter", l.getBalanceAfter());
    }

    /**
     * 划扣流水批量投影（B6 双账核对）：GET /api/customer/internal/cards/writeoff-ledgers?bizRefs=WO…&amp;bizRefs=…。
     * 按 WO 划扣单号批量返回 card_ledger CONSUME 流水（含 amount/operator，system-backfill 为回填补记行）；
     * txn 据此比对「划扣记录 ↔ 权威流水」缺失/金额差异，不直读 card_ledger 表。无匹配返回空数组。
     */
    @GetMapping("/cards/writeoff-ledgers")
    @RequirePerm("internal:card-balance")
    public List<WriteoffLedgerDTO> writeoffLedgers(@RequestParam("bizRefs") List<String> bizRefs) {
        if (bizRefs == null || bizRefs.isEmpty()) return List.of();
        List<String> refs = bizRefs.stream().filter(s -> s != null && !s.isBlank()).toList();
        if (refs.isEmpty()) return List.of();
        return ledgerRepo.findByBizRefIn(refs).stream()
                .map(l -> new WriteoffLedgerDTO(l.getLedgerId(), l.getCardNo(), l.getBizRef(),
                        l.getChangeType(), l.getAmount(), l.getOperator(), l.getStoreCode()))
                .toList();
    }

    /**
     * 单卡余额变动时间线投影（B24 卡2，finance 卡余额详情懒加载）：
     * GET /api/customer/internal/cards/{cardNo}/ledger。
     * 返回卡快照（客户/卡项/剩余次数/状态）+ card_ledger 账龄正序全量流水（金额单位分）；
     * 卡不存在返回 404 中文提示。数据权限收敛（门店/登录人）由 finance-service 二次过滤，
     * 本端点仅以系统身份（X-Internal-Token）开放，财务域不直读 card_ledger 表。
     */
    @GetMapping("/cards/{cardNo}/ledger")
    @RequirePerm("internal:card-balance")
    public CardLedgerBundleDTO cardLedger(@org.springframework.web.bind.annotation.PathVariable("cardNo") String cardNo) {
        if (cardNo == null || cardNo.isBlank()) {
            throw new CardLedgerService.BadReq("卡号不能为空");
        }
        MemberCard card = cardRepo.findById(cardNo)
                .orElseThrow(() -> new CardLedgerService.NotFound("会员卡不存在: " + cardNo));
        String customerName = customerRepo.findById(card.getCustomerId())
                .map(Customer::getName).orElse(card.getCustomerId());
        List<CardLedgerItemDTO> items = ledgerService.listLedger(cardNo).stream()
                .map(l -> new CardLedgerItemDTO(l.getLedgerId(), l.getCardNo(), l.getCustomerId(),
                        l.getChangeType(), l.getAmount(), l.getBalanceAfter(),
                        l.getGiftAmount() == null ? 0L : l.getGiftAmount(),
                        l.getGiftAfter() == null ? 0L : l.getGiftAfter(),
                        l.getBizRef() == null ? "" : l.getBizRef(),
                        l.getOrderNo() == null ? "" : l.getOrderNo(),
                        l.getOperator() == null ? "" : l.getOperator(),
                        l.getStoreCode() == null ? "" : l.getStoreCode(),
                        l.getCreatedAt() == null ? "" : l.getCreatedAt().toString()))
                .toList();
        return new CardLedgerBundleDTO(card.getCardNo(), card.getCustomerId(), customerName,
                card.getCardItem(), card.getStoreCode() == null ? "" : card.getStoreCode(),
                card.getCardType() == null ? "" : card.getCardType(),
                card.getProductCode() == null ? "" : card.getProductCode(),
                card.getBalance(), card.getGiftBalance() == null ? 0L : card.getGiftBalance(),
                card.getTotalTimes(), card.getRemainTimes(), card.getStatus(), items);
    }

    /**
     * 售卡开卡联动（B16，txn 售卡订单收款收齐后回调）：POST /api/customer/internal/cards/issue。
     * 客户域实例化 member_card（product_code/card_type/expires_at/sale_no/gift_balance 溯源落库）并写
     * 首笔 RECHARGE 正额流水（bizRef=orderNo）；以售卡订单号 sale_no 幂等，收款回调重试不重复开卡；
     * 客户不存在 404 / 参数非法 400 均中文透传，txn 收款事务整笔回滚（杜绝「收款办结但卡未开」）。
     * 资金分录（RF-DEPOSIT/IN 预收）由 txn 域 outbox 投递，本端点只开卡与动卡台账。
     */
    @PostMapping("/cards/issue")
    @RequirePerm("internal:card-write")
    public Map<String, Object> issue(@RequestBody IssueCmd cmd) {
        if (cmd == null) throw new CardLedgerService.BadReq("请求体不能为空");
        MemberCard card = ledgerService.issue(
                cmd.orderNo(), cmd.customerId(), cmd.storeCode(),
                cmd.productCode(), cmd.cardType(), cmd.cardItem(),
                cmd.totalTimes() == null ? 0 : cmd.totalTimes(),
                cmd.validityDays() == null ? 0 : cmd.validityDays(),
                cmd.priceFen() == null ? 0L : cmd.priceFen(),
                cmd.giftBalance() == null ? 0L : cmd.giftBalance(),
                cmd.operator());
        return Map.of(
                "cardNo", card.getCardNo(),
                "customerId", card.getCustomerId(),
                "balanceAfter", card.getBalance(),
                "giftBalance", card.getGiftBalance() == null ? 0L : card.getGiftBalance(),
                "totalTimes", card.getTotalTimes(),
                "remainTimes", card.getRemainTimes(),
                "status", card.getStatus() == null ? "在用" : card.getStatus());
    }

    /** 储值扣款入参：cardNo/customerId/amount（分，&gt;0）/orderNo（幂等键）。 */
    public record ConsumeCmd(String cardNo, String customerId, Long amount, String orderNo) {}

    /** 客户目录投影：customerId/姓名/归属门店（空串=集团/公海）/中文状态。不含手机号等敏感字段。 */
    public record CustomerDirectoryDTO(String customerId, String name, String storeCode, String status) {}

    /** 划扣流水投影：ledgerId/cardNo/bizRef（WO 单号）/changeType/amount（负额分，0 为纯扣次）/operator（system-backfill 为回填行）/storeCode。 */
    public record WriteoffLedgerDTO(Long ledgerId, String cardNo, String bizRef, String changeType,
                                    Long amount, String operator, String storeCode) {}

    /** 单卡时间线明细行：card_ledger 全字段投影，金额单位分；gift 两列历史空行回落 0；bizRef/orderNo/operator/storeCode 空串归一。 */
    public record CardLedgerItemDTO(Long ledgerId, String cardNo, String customerId, String changeType,
                                    Long amount, Long balanceAfter, Long giftAmount, Long giftAfter,
                                    String bizRef, String orderNo, String operator, String storeCode,
                                    String createdAt) {}

    /** 单卡时间线响应：卡快照（客户/卡项/类型/产品/本金赠金/次数/中文状态）+ 账龄正序流水。 */
    public record CardLedgerBundleDTO(String cardNo, String customerId, String customerName,
                                      String cardItem, String storeCode, String cardType,
                                      String productCode, Long balance, Long giftBalance,
                                      Integer totalTimes, Integer remainTimes, String status,
                                      List<CardLedgerItemDTO> ledger) {}

    /** 疗程划扣入参：cardNo/writeoffId（WO 单号，幂等键）/timesUsed（扣次，≥1）/amount（分，≥0，0 为纯扣次）/storeCode/backfill（存量回填标记）。 */
    public record WriteoffCmd(String cardNo, String writeoffId, Integer timesUsed, Long amount,
                              String storeCode, Boolean backfill) {}

    /** 退卡回写入参：cardNo/cancelNo（退卡 CC 单号，幂等键）。 */
    public record RefundCmd(String cardNo, String cancelNo) {}

    /** 退卡冻结/解冻入参：cardNo/cancelNo（退卡 CC 单号；冻结流水 bizRef=cancelNo-F、解冻=cancelNo-U）。 */
    public record FreezeCmd(String cardNo, String cancelNo) {}

    /** 订单退款回加入参：refundNo（退款 RF 单号，幂等键）/orderNo（原订单号，反查扣款卡）/amount（分，&gt;0）。 */
    public record RefundOrderCmd(String refundNo, String orderNo, Long amount) {}

    /**
     * 售卡开卡入参：orderNo（售卡订单 OD 单号，开卡幂等键）/customerId/storeCode/
     * productCode（CD-/CS- 模板编码）/cardType（CARD/COURSE 快照）/cardItem（卡名快照）/
     * totalTimes（模板次数，储值卡=1）/validityDays（有效期天数）/priceFen（售价分，首笔充值额）/
     * giftBalance（赠送金分，≥0）/operator（收银操作人，仅审计留痕，台账动账人记 system）。
     */
    public record IssueCmd(String orderNo, String customerId, String storeCode, String productCode,
                           String cardType, String cardItem, Integer totalTimes, Integer validityDays,
                           Long priceFen, Long giftBalance, String operator) {}
}
