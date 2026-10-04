package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T2-B1 数据治理端点（/api/customer/t2/govern，DESIGN-T2，按域落 customer-service）。
 * 权限三码零新码（PermissionMatrix L123/L269-270 预埋）：查询=govern:view；
 * 规则创建=govern:rule:create；规则编辑/启停＋问题单解决/忽略=govern:rule:edit。
 */
@RestController
@RequestMapping("/api/customer/t2/govern")
public class T2GovernController {

    private final T2GovernService service;

    public T2GovernController(T2GovernService service) {
        this.service = service;
    }

    /** 规则新建/编辑请求体（字段名对齐前端契约；编辑为 patch 语义全可空）。 */
    public record RuleReq(String name, String table, String column, String type,
                          String severity, String expression, Boolean enabled) {}

    @GetMapping("/rules")
    @RequirePerm("govern:view")
    public List<T2GovernService.RuleView> rules() {
        return service.listRules();
    }

    @PostMapping("/rules")
    @RequirePerm("govern:rule:create")
    public T2GovernService.RuleView createRule(@RequestBody RuleReq req) {
        return service.createRule(req.name(), req.table(), req.column(), req.type(),
                req.severity(), req.expression(), req.enabled());
    }

    @PutMapping("/rules/{id}")
    @RequirePerm("govern:rule:edit")
    public T2GovernService.RuleView updateRule(@PathVariable Long id, @RequestBody RuleReq req) {
        return service.updateRule(id, req.name(), req.table(), req.column(), req.type(),
                req.severity(), req.expression(), req.enabled());
    }

    @PostMapping("/rules/{id}/toggle")
    @RequirePerm("govern:rule:edit")
    public T2GovernService.RuleView toggleRule(@PathVariable Long id) {
        return service.toggleRule(id);
    }

    /** 手动执行质量规则：真回填三统计＋违规幂等检出 OPEN 问题单（禁用规则 400 中文透出）。 */
    @PostMapping("/rules/{id}/run")
    @RequirePerm("govern:rule:edit")
    public T2GovernService.RuleRunResult runRule(@PathVariable Long id) {
        return service.runRule(id);
    }

    @GetMapping("/issues")
    @RequirePerm("govern:view")
    public List<T2GovernService.IssueView> issues() {
        return service.listIssues();
    }

    @PostMapping("/issues/{id}/resolve")
    @RequirePerm("govern:rule:edit")
    public T2GovernService.IssueView resolveIssue(@PathVariable Long id) {
        return service.resolveIssue(id);
    }

    @PostMapping("/issues/{id}/ignore")
    @RequirePerm("govern:rule:edit")
    public T2GovernService.IssueView ignoreIssue(@PathVariable Long id) {
        return service.ignoreIssue(id);
    }

    @GetMapping("/lineage")
    @RequirePerm("govern:view")
    public T2GovernService.LineageView lineage() {
        return service.lineage();
    }
}
