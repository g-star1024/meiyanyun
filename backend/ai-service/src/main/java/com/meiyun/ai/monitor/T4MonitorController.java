package com.meiyun.ai.monitor;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T4 监控告警业务出口（/ai/monitor）。
 * 类级权限 monitor:view；写端点方法级细码（拦截语义为方法级覆盖类级）：
 * 创建=monitor:rule:create / 更新+启停=monitor:rule:edit；
 * ack/resolve 无权限码照 mock 仅登录（权限矩阵零新码，契约④）。
 */
@RestController
@RequestMapping("/api/ai/t4/monitor")
@RequirePerm("monitor:view")
public class T4MonitorController {

    private final T4MonitorService t4MonitorService;

    public T4MonitorController(T4MonitorService t4MonitorService) {
        this.t4MonitorService = t4MonitorService;
    }

    @GetMapping
    public T4MonitorService.MonitorOverview overview() {
        return t4MonitorService.overview();
    }

    @PostMapping("/rules")
    @RequirePerm("monitor:rule:create")
    public T4MonitorService.RuleView createRule(@RequestBody T4MonitorService.CreateRuleCmd cmd) {
        return t4MonitorService.createRule(cmd, DataScope.currentActor());
    }

    @PutMapping("/rules/{code}")
    @RequirePerm("monitor:rule:edit")
    public T4MonitorService.RuleView updateRule(@PathVariable String code,
                                                @RequestBody T4MonitorService.UpdateRuleCmd cmd) {
        return t4MonitorService.updateRule(code, cmd, DataScope.currentActor());
    }

    @PostMapping("/rules/{code}/enabled")
    @RequirePerm("monitor:rule:edit")
    public T4MonitorService.RuleView toggleRule(@PathVariable String code,
                                                @RequestBody T4MonitorService.ToggleCmd cmd) {
        return t4MonitorService.toggleRule(code, cmd, DataScope.currentActor());
    }

    @PostMapping("/events/{code}/acknowledge")
    public T4MonitorService.EventView acknowledge(@PathVariable String code) {
        return t4MonitorService.acknowledge(code, DataScope.currentActor());
    }

    @PostMapping("/events/{code}/resolve")
    public T4MonitorService.EventView resolve(@PathVariable String code) {
        return t4MonitorService.resolve(code, DataScope.currentActor());
    }
}
