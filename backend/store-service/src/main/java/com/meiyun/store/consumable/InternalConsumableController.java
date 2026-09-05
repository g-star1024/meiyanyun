package com.meiyun.store.consumable;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 耗材出库内部端点（B5，服务间调用专用）：txn 审批中心对领用/报损终审通过后同步回调。
 *
 * <p>红线边界：库存扣减是双签终审的下游动作，不对业务页面开放——仅以系统身份
 * （X-Internal-Token，perms=["*"]）放行，普通登录人无 {@code internal:consumable-write}
 * 权限 → 403。txn 域不直写 consumable_* 表，由 store 域按自身实体语义扣库；
 * 同事务调用失败则 txn 终审回滚（4xx 中文透传 / 5xx 502）。
 * 幂等：bizRef（审批待办号 AP...）+ SKU 已存在流水即跳过，重试不双扣。
 */
@RestController
@RequestMapping("/api/stores/internal/consumables")
public class InternalConsumableController {

    private final ConsumableService service;

    public InternalConsumableController(ConsumableService service) {
        this.service = service;
    }

    /**
     * 领用/报损出库扣减：POST /api/stores/internal/consumables/deduct。
     * moveType=USE 领用（→ TK-MATERIAL）/ SCRAP 报损（→ TK-LOSS）；
     * 库存不足整批 422 中文回滚；返回各 SKU 定格单价与金额（分），供 txn 写成本 outbox。
     */
    @PostMapping("/deduct")
    @RequirePerm("internal:consumable-write")
    public Map<String, Object> deduct(@RequestBody DeductCmd cmd) {
        if (cmd == null) throw ConsumableService.badReq("请求体不能为空");
        List<ConsumableService.DeductLineResult> results = service.deduct(
                cmd.bizRef(), cmd.storeCode(), cmd.lines(), cmd.moveType(), cmd.operator());
        long totalFen = results.stream().mapToLong(ConsumableService.DeductLineResult::amountFen).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bizRef", cmd.bizRef());
        out.put("moveType", cmd.moveType());
        out.put("lineCount", results.size());
        out.put("totalAmountFen", totalFen);
        out.put("lines", results);
        return out;
    }

    /** 出库入参：bizRef 审批待办号（幂等键）/storeCode/moveType USE|SCRAP/operator 终审人/lines 明细 */
    public record DeductCmd(String bizRef, String storeCode, String moveType, String operator,
                            List<ConsumableService.DeductLine> lines) {}
}
