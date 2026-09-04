package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/txn")
public class TxnController {

    private final TxnService txnService;

    public TxnController(TxnService txnService) {
        this.txnService = txnService;
    }

    /** 退款申请（RF）。手续费默认 0，可经 feeManualOverride 手动窗口覆盖；L1 直达财务复核，L2/L3 待店长审批。 */
    @PostMapping("/refund")
    @RequirePerm("refund:create")
    public TxnRefund createRefund(@RequestBody @Valid TxnService.CreateRefundCmd cmd) {
        return txnService.createRefund(cmd);
    }

    /** 退卡申请（CC）。手续费默认 10%；医疗禁忌确诊默认免收；可经手动窗口逐单覆盖。 */
    @PostMapping("/card-cancel")
    @RequirePerm("cardcancel:create")
    public TxnCardCancel createCardCancel(@RequestBody @Valid TxnService.CreateCardCancelCmd cmd) {
        return txnService.createCardCancel(cmd);
    }

    /** 双签 / 三签步骤（硬校验未过抛 400；旧端点保留兼容）。 */
    @PostMapping("/{txnNo}/sign")
    @RequirePerm({"refund:sign", "cardcancel:sign"})
    public void sign(@PathVariable String txnNo, @RequestBody @Valid TxnService.SignCommand cmd) {
        txnService.sign(txnNo, cmd);
    }

    /** 退款单列表（可按 status=PENDING_REVIEW/PENDING_FINANCE/REFUNDED/REJECTED 过滤）。 */
    @GetMapping("/refund")
    @RequirePerm("refund:view")
    public List<TxnRefund> refunds(@RequestParam(required = false) String status) {
        return txnService.listRefunds(status);
    }

    /** 退卡单列表（可按 status 过滤）。 */
    @GetMapping("/card-cancel")
    @RequirePerm("cardcancel:view")
    public List<TxnCardCancel> cardCancels(@RequestParam(required = false) String status) {
        return txnService.listCardCancels(status);
    }

    @GetMapping("/refund/{no}")
    @RequirePerm("refund:view")
    public TxnRefund refund(@PathVariable String no) {
        return txnService.findRefund(no);
    }

    @GetMapping("/card-cancel/{no}")
    @RequirePerm("cardcancel:view")
    public TxnCardCancel cardCancel(@PathVariable String no) {
        return txnService.findCardCancel(no);
    }

    /** 店长/运营一审通过：PENDING_REVIEW → PENDING_FINANCE（RF/CC 共用，按单号前缀识别）。 */
    @PostMapping("/{txnNo}/approve")
    @RequirePerm({"refund:approve", "cardcancel:approve"})
    public void approve(@PathVariable String txnNo, @RequestBody(required = false) TxnService.ApprovalCmd cmd) {
        txnService.approve(txnNo, cmd == null ? new TxnService.ApprovalCmd(null, null) : cmd);
    }

    /** 驳回：PENDING_REVIEW / PENDING_FINANCE → REJECTED（comment 必填）。 */
    @PostMapping("/{txnNo}/reject")
    @RequirePerm({"refund:approve", "cardcancel:approve"})
    public void reject(@PathVariable String txnNo, @RequestBody TxnService.ApprovalCmd cmd) {
        txnService.reject(txnNo, cmd);
    }

    /** 财务终审确认退款/退卡完成：PENDING_FINANCE → REFUNDED（资金出入账 M6 补）。 */
    @PostMapping("/{txnNo}/confirm")
    @RequirePerm({"refund:sign", "cardcancel:sign"})
    public void confirm(@PathVariable String txnNo, @RequestBody(required = false) TxnService.ApprovalCmd cmd) {
        txnService.confirmRefund(txnNo, cmd == null ? new TxnService.ApprovalCmd(null, null) : cmd);
    }
}
