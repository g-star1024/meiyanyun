package com.meiyun.customer;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会员卡储值端点（B4 充值储值，对外收银/卡 360 页面）。
 *
 * <p>精确路径 {@code /cards/...} 优先于 {@code /{id}} 匹配，与 name-map/phone-map 同例，
 * 不会与客户详情 {@code GET /api/customer/{id}} 冲突。
 *
 * <p>写接口四件套：权限码 customer:card:recharge（收银/店长/超管）；卡不存在或越权统一 404
 * （不泄露卡是否存在）；金额/支付方式服务端白名单校验（充值禁用 balance）；操作人/门店取登录
 * 上下文，前端不可伪造；RC 单号服务端生成，重放 409；全审计落 CARD 链。
 */
@RestController
@RequestMapping("/api/customer/cards")
public class CardController {

    private final CardLedgerService ledgerService;
    private final MemberCardRepository cardRepo;

    public CardController(CardLedgerService ledgerService, MemberCardRepository cardRepo) {
        this.ledgerService = ledgerService;
        this.cardRepo = cardRepo;
    }

    /** 会员卡充值：POST /api/customer/cards/{cardNo}/recharge（amount 分；payMethod cash/card/wxpay/alipay）。 */
    @PostMapping("/{cardNo}/recharge")
    @RequirePerm("customer:card:recharge")
    public Map<String, Object> recharge(@PathVariable("cardNo") String cardNo,
                                        @RequestBody RechargeReq req) {
        MemberCard card = cardRepo.findById(cardNo)
                .orElseThrow(() -> new CustomerService.NotFound("数据不存在或无权查看"));
        if (!DataScope.canReadStore(card.getStoreCode())) {
            throw new CustomerService.NotFound("数据不存在或无权查看");
        }
        long amount = req.amount() == null ? 0L : req.amount();
        String operator = DataScope.currentActor();
        CardLedger l = ledgerService.recharge(cardNo, amount,
                req.payMethod() == null ? "" : req.payMethod().trim(),
                operator, card.getStoreCode());
        return Map.of(
                "ledgerId", l.getLedgerId(),
                "bizRef", l.getBizRef(),
                "balanceAfter", l.getBalanceAfter());
    }

    /** 卡储值流水：GET /api/customer/cards/{cardNo}/ledger（卡详情「储值流水」读模型，账龄正序）。 */
    @GetMapping("/{cardNo}/ledger")
    @RequirePerm("customer:view")
    public List<Map<String, Object>> ledger(@PathVariable("cardNo") String cardNo) {
        MemberCard card = cardRepo.findById(cardNo)
                .orElseThrow(() -> new CustomerService.NotFound("数据不存在或无权查看"));
        if (!DataScope.canReadStore(card.getStoreCode())) {
            throw new CustomerService.NotFound("数据不存在或无权查看");
        }
        return ledgerService.listLedger(cardNo).stream()
                .map(l -> Map.<String, Object>of(
                        "ledgerId", l.getLedgerId(),
                        "changeType", l.getChangeType(),
                        "amount", l.getAmount(),
                        "balanceAfter", l.getBalanceAfter(),
                        "bizRef", l.getBizRef() == null ? "" : l.getBizRef(),
                        "operator", l.getOperator() == null ? "" : l.getOperator(),
                        "createdAt", l.getCreatedAt() == null ? "" : l.getCreatedAt().toString()))
                .toList();
    }

    /** 充值入参：amount 分（&gt;0）、payMethod（cash/card/wxpay/alipay，禁用 balance）；operator/storeCode 由服务端注入。 */
    public record RechargeReq(Long amount, String payMethod) {}
}
