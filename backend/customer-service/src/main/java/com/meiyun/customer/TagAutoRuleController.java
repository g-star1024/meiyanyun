package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签自动化规则 REST（域①-257）。读/写分别受 tag:view / tag:edit 约束。
 * 写操作落 TAG_AUTO 审计；定时扫描由 {@link TagAutoRuleJob} 驱动，手动触发入口亦在此。
 */
@RestController
@RequestMapping("/api/customer/tag-rules")
public class TagAutoRuleController {

    private final TagAutoRuleService service;
    private final AuditRecorder audit;

    public TagAutoRuleController(TagAutoRuleService service, AuditRecorder audit) {
        this.service = service;
        this.audit = audit;
    }

    /** 规则列表（分页）。 */
    @GetMapping
    @RequirePerm("tag:view")
    public Page<TagAutoRule> list(@PageableDefault(size = 50, sort = "priority") Pageable pageable) {
        return service.listRules(pageable);
    }

    /** 新建规则：四件套校验 + 目标标签存在性；落 TAG_AUTO/CREATE。 */
    @PostMapping
    @RequirePerm("tag:edit")
    public TagAutoRule create(@RequestBody TagRuleReq req) {
        TagAutoRule r = service.createRule(
                req == null ? null : req.name(), req == null || req.enabled() == null || req.enabled(),
                req == null ? null : req.effect(), req == null ? null : req.targetTagId(),
                req == null ? null : req.conditionType(), req == null ? null : req.conditionValue(),
                req == null ? null : req.priority(),
                req != null && req.revokeWhenUnsatisfied() != null && req.revokeWhenUnsatisfied());
        audit.record("TAG_AUTO", r.getRuleId(), DataScope.currentActor(), "CREATE", json(r));
        return r;
    }

    /** 更新规则：落 TAG_AUTO/UPDATE（记录变更后值）。 */
    @PutMapping("/{ruleId}")
    @RequirePerm("tag:edit")
    public TagAutoRule update(@PathVariable String ruleId, @RequestBody TagRuleReq req) {
        TagAutoRule r = service.updateRule(ruleId,
                req == null ? null : req.name(),
                req == null ? null : req.enabled(),
                req == null ? null : req.effect(),
                req == null ? null : req.targetTagId(),
                req == null ? null : req.conditionType(),
                req == null ? null : req.conditionValue(),
                req == null ? null : req.priority(),
                req == null ? null : req.revokeWhenUnsatisfied());
        audit.record("TAG_AUTO", ruleId, DataScope.currentActor(), "UPDATE", json(r));
        return r;
    }

    /** 删除规则：落 TAG_AUTO/DELETE。 */
    @DeleteMapping("/{ruleId}")
    @RequirePerm("tag:edit")
    public Map<String, Object> delete(@PathVariable String ruleId) {
        service.deleteRule(ruleId);
        audit.record("TAG_AUTO", ruleId, DataScope.currentActor(), "DELETE",
                "{\"ruleId\":\"" + esc(ruleId) + "\"}");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deleted", ruleId);
        return m;
    }

    /**
     * 手动执行单条规则（即时扫描全部客户打/撤标）；有实际变更时落 TAG_AUTO/RULE_RUN 汇总审计。
     * 定时任务 {@link TagAutoRuleJob} 周期性执行同样的入口。
     */
    @PostMapping("/{ruleId}/run")
    @RequirePerm("tag:edit")
    public TagAutoRuleService.RuleRunResult runOne(@PathVariable String ruleId) {
        TagAutoRule rule = service.listRules().stream()
                .filter(r -> r.getRuleId().equals(ruleId)).findFirst()
                .orElseThrow(() -> new CustomerService.NotFound("规则不存在: " + ruleId));
        TagAutoRuleService.RuleRunResult result = service.runRule(rule);
        if (result.assigned() > 0 || result.revoked() > 0) {
            audit.record("TAG_AUTO", ruleId, DataScope.currentActor(), "RULE_RUN",
                    "{\"ruleId\":\"" + esc(ruleId) + "\",\"scanned\":" + result.scanned()
                            + ",\"matched\":" + result.matched() + ",\"assigned\":" + result.assigned()
                            + ",\"revoked\":" + result.revoked() + "}");
        }
        return result;
    }

    /** 手动执行全部启用规则（按优先级升序）。 */
    @PostMapping("/run-all")
    @RequirePerm("tag:edit")
    public List<TagAutoRuleService.RuleRunResult> runAll() {
        List<TagAutoRuleService.RuleRunResult> results = service.runAll();
        audit.record("TAG_AUTO", "RUN-ALL", DataScope.currentActor(), "RULE_RUN_ALL",
                "{\"rules\":" + results.size() + "}");
        return results;
    }

    private static String json(TagAutoRule r) {
        return "{\"ruleId\":\"" + esc(r.getRuleId()) + "\",\"name\":\"" + esc(r.getName())
                + "\",\"enabled\":" + r.getEnabled() + ",\"effect\":\"" + esc(r.getEffect())
                + "\",\"targetTagId\":\"" + esc(r.getTargetTagId()) + "\",\"conditionType\":\""
                + esc(r.getConditionType()) + "\",\"conditionValue\":\"" + esc(r.getConditionValue())
                + "\",\"priority\":" + r.getPriority()
                + ",\"revokeWhenUnsatisfied\":" + r.getRevokeWhenUnsatisfied() + "}";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 规则请求体：全字段可空（更新时局部更新）；创建时除 priority/revokeWhenUnsatisfied 外必填。 */
    public record TagRuleReq(String name, Boolean enabled, String effect, String targetTagId,
                             String conditionType, String conditionValue, Integer priority,
                             Boolean revokeWhenUnsatisfied) {}
}
