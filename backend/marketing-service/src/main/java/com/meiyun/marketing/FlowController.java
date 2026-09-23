package com.meiyun.marketing;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Flow 规则与执行日志端点（P5-B90，v1 仅 REST 无前端页，DESIGN §3）。
 *
 * <p>权限：类级 marketing:view（查询），增改/启停方法级 marketing:edit（D3 零新码）。
 * 默认三规则由 V48 播种；执行日志由 {@link MarketingFlowJob} 扫描落账（SUCCESS/SKIPPED/FAILED）。
 */
@RestController
@RequestMapping("/api/marketing/flow")
@RequirePerm("marketing:view")
public class FlowController {

    private final FlowService flowService;

    public FlowController(FlowService flowService) {
        this.flowService = flowService;
    }

    /** 规则列表（enabled 可空=全部）。 */
    @GetMapping("/rules")
    public List<FlowService.RuleView> rules(@RequestParam(required = false) Boolean enabled) {
        return flowService.list(enabled);
    }

    @PostMapping("/rules")
    @RequirePerm("marketing:edit")
    public FlowService.RuleView create(@RequestBody FlowService.RuleCmd cmd) {
        return flowService.create(cmd);
    }

    @PutMapping("/rules/{id}")
    @RequirePerm("marketing:edit")
    public FlowService.RuleView update(@PathVariable Long id, @RequestBody FlowService.RuleCmd cmd) {
        return flowService.update(id, cmd);
    }

    /** 启停翻转（误配止血，DESIGN §7）。 */
    @PostMapping("/rules/{id}/toggle")
    @RequirePerm("marketing:edit")
    public FlowService.RuleView toggle(@PathVariable Long id) {
        return flowService.toggle(id);
    }

    /** 执行日志（ruleNo/date=yyyy-MM-dd 可空四组合，无过滤限 200 条）。 */
    @GetMapping("/logs")
    public List<FlowService.LogView> logs(@RequestParam(required = false) String ruleNo,
                                          @RequestParam(required = false) String date) {
        return flowService.logs(ruleNo, date);
    }
}
