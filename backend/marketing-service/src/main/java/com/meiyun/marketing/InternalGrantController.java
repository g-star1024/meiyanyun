package com.meiyun.marketing;

import com.meiyun.security.RequirePerm;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 赠金域内部端点（服务间调用，不经网关；网关对 /internal/** 一律 404）。
 *
 * <p>调用方：txn-service 收银台收款。鉴权走 X-Internal-Token（拦截器注入 perms=["*"]），
 * 普通登录人即使拿到路径也因缺 internal:grant-write 被 403 挡下。
 */
@RestController
@RequestMapping("/api/marketing/internal")
public class InternalGrantController {

    private final GrantService grantService;

    public InternalGrantController(GrantService grantService) {
        this.grantService = grantService;
    }

    /** 收银台赠金抵扣：按到期时间 FIFO 跨券扣减，同订单号重放不双扣。 */
    @PostMapping("/grants/deduct")
    @RequirePerm("internal:grant-write")
    public Map<String, Object> deduct(@RequestBody DeductCmd cmd) {
        if (cmd == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        List<GrantDeduction> rows = grantService.deduct(
                cmd.customerId(), cmd.amountFen(), cmd.bizRef(), cmd.storeCode(), cmd.operator());
        long deducted = rows.stream().mapToLong(GrantDeduction::getAmountFen).sum();
        return Map.of(
                "bizRef", cmd.bizRef() == null ? "" : cmd.bizRef().trim(),
                "deductedFen", deducted,
                "grantCount", rows.size(),
                "balanceFen", grantService.balance(cmd.customerId().trim()));
    }

    /** 退款终审赠金回加：逆向 FIFO 回补原券行，同退款单号重放不双加（B39）。 */
    @PostMapping("/grants/refund")
    @RequirePerm("internal:grant-write")
    public Map<String, Object> refund(@RequestBody RefundCmd cmd) {
        if (cmd == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        List<GrantDeduction> rows = grantService.refund(
                cmd.customerId(), cmd.amountFen(), cmd.orderNo(), cmd.refundNo(),
                cmd.storeCode(), cmd.operator());
        long refunded = rows.stream().mapToLong(GrantDeduction::getAmountFen).sum();
        return Map.of(
                "bizRef", cmd.refundNo() == null ? "" : cmd.refundNo().trim(),
                "refundedFen", refunded,
                "grantCount", rows.size(),
                "balanceFen", grantService.balance(cmd.customerId().trim()));
    }

    /** 收银台可用赠金余额（下单前预检，避免提交后才报余额不足）。 */
    @GetMapping("/grants/balance")
    @RequirePerm("internal:grant-balance")
    public Map<String, Object> balance(@RequestParam String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户ID不可为空");
        }
        return Map.of("customerId", customerId.trim(),
                "balanceFen", grantService.balance(customerId.trim()));
    }

    /**
     * 赠金抵扣入参。
     *
     * @param customerId 客户ID（必填）
     * @param amountFen  本次抵扣金额（分，&gt; 0；余额不足直接 422 拒绝，不做部分抵扣）
     * @param bizRef     幂等键=订单号（必填，同单重放返回既有流水）
     * @param storeCode  抵扣发生门店（取自订单，仅留痕）
     * @param operator   收银员工号（内部调用下审计 actor 为 system，真实操作人靠本字段留痕）
     */
    public record DeductCmd(String customerId, Long amountFen, String bizRef,
                            String storeCode, String operator) {}

    /**
     * 退款回加入参（B39）。
     *
     * @param customerId 客户ID（必填）
     * @param amountFen  本次退款的赠金段金额（分，&gt; 0；由交易域按「赠金→卡本金→法币」级联拆出）
     * @param orderNo    原订单号（必填，作 origin_biz_ref 汇总累计回加额封顶）
     * @param refundNo   退款单号（必填，作 biz_ref 幂等键，同终审重放返回既有流水）
     * @param storeCode  退款发生门店（取自退款单，仅留痕）
     * @param operator   终审操作人工号（内部调用下审计 actor 为 system，真实操作人靠本字段留痕）
     */
    public record RefundCmd(String customerId, Long amountFen, String orderNo, String refundNo,
                            String storeCode, String operator) {}
}
