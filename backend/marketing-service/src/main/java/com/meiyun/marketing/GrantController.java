package com.meiyun.marketing;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 赠金高级规则控制器（域⑤ 赠金）。独立新建，不修改既有 MarketingController。
 * 路径前缀 /api/marketing/grants，权限码沿用营销域约定（grant:edit 写、marketing:view 读）。
 */
@RestController
@RequestMapping("/api/marketing/grants")
public class GrantController {

    private final GrantService grantService;

    public GrantController(GrantService grantService) {
        this.grantService = grantService;
    }

    @GetMapping("/rules")
    @RequirePerm("marketing:view")
    public List<GrantRule> rules() {
        return grantService.listRules();
    }

    @PostMapping("/rules")
    @RequirePerm("grant:edit")
    public GrantRule createRule(@RequestBody GrantService.CreateRuleCmd cmd) {
        return grantService.createRule(cmd);
    }

    @PutMapping("/rules/{id}")
    @RequirePerm("grant:edit")
    public Map<String, Object> updateRule(@PathVariable String id, @RequestBody GrantService.CreateRuleCmd cmd) {
        return Map.of("changed", grantService.updateRule(id, cmd));
    }

    @PostMapping("/rules/{id}/toggle")
    @RequirePerm("grant:edit")
    public Map<String, Object> toggleRule(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        boolean enabled = body == null || !Boolean.FALSE.equals(body.get("enabled"));
        return Map.of("changed", grantService.toggleRule(id, enabled));
    }

    /** 手动发放赠金（运营补偿/定向活动）。 */
    @PostMapping("/issue")
    @RequirePerm("grant:edit")
    public CustomerGrant issue(@RequestBody GrantService.IssueCmd cmd) {
        return grantService.issueGrant(cmd);
    }

    @GetMapping("/customer/{customerId}")
    @RequirePerm("marketing:view")
    public Map<String, Object> customerBalance(@PathVariable String customerId) {
        return Map.of("customerId", customerId, "balanceFen", grantService.balance(customerId));
    }

    @GetMapping("/report")
    @RequirePerm("marketing:view")
    public Map<String, Object> report() {
        return grantService.report();
    }
}
