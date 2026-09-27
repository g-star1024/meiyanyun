package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 黑名单与风控 REST（M3-B6 / DESIGN-M3 §3 M3-17）。
 * 读受 risk:view；提交拉黑/解除风险/规则启停受 risk:edit；审核通过/驳回受 risk:approve。
 * 状态机前置校验在服务层（中文 4xx），请求体不接 actor（取 DataScope.currentActor()，防伪造）。
 */
@RestController
@RequestMapping("/api/customer/m3/risk")
public class RiskController {

    private final RiskService service;

    public RiskController(RiskService service) {
        this.service = service;
    }

    /** 风控名单（命中次数倒序，与前端 filtered 排序口径一致）。 */
    @GetMapping("/records")
    @RequirePerm("risk:view")
    public List<RiskService.RiskRecordView> records() {
        return service.listRecords();
    }

    /** 风控规则（按 id 升序，种子 RR-1..RR-5 序）。 */
    @GetMapping("/rules")
    @RequirePerm("risk:view")
    public List<RiskService.RiskRuleView> rules() {
        return service.listRules();
    }

    /** 提交拉黑审核：新建 PENDING_REVIEW 记录，HIGH 级别默认拦截交易；落 RISK/SUBMIT 审计。 */
    @PostMapping("/records")
    @RequirePerm("risk:edit")
    public RiskService.RiskRecordView submit(@RequestBody SubmitReq req) {
        return service.submit(
                req == null ? null : req.customerName(),
                req == null ? null : req.phoneMask(),
                req == null ? null : req.level(),
                req == null ? null : req.reason(),
                req == null ? null : req.detail());
    }

    /** 审核通过：PENDING_REVIEW→BLACKLISTED，拦截交易并记处置人；落 RISK/APPROVE 审计。 */
    @PostMapping("/records/{id}/approve")
    @RequirePerm("risk:approve")
    public RiskService.RiskRecordView approve(@PathVariable Long id) {
        return service.approve(id);
    }

    /** 审核驳回：PENDING_REVIEW→WATCHING，原因必填；落 RISK/REJECT 审计。 */
    @PostMapping("/records/{id}/reject")
    @RequirePerm("risk:approve")
    public RiskService.RiskRecordView reject(@PathVariable Long id, @RequestBody ReasonReq req) {
        return service.reject(id, req == null ? null : req.reason());
    }

    /** 解除风险：BLACKLISTED|WATCHING→RELEASED，原因必填并记处置人；落 RISK/RELEASE 审计。 */
    @PostMapping("/records/{id}/release")
    @RequirePerm("risk:edit")
    public RiskService.RiskRecordView release(@PathVariable Long id, @RequestBody ReasonReq req) {
        return service.release(id, req == null ? null : req.reason());
    }

    /** 规则启停开关；落 RISK/RULE_TOGGLE 审计。 */
    @PostMapping("/rules/{id}/toggle")
    @RequirePerm("risk:edit")
    public RiskService.RiskRuleView toggleRule(@PathVariable Long id) {
        return service.toggleRule(id);
    }

    /** 提交拉黑请求体：customerName/level/reason/detail 必填（服务层校验），phoneMask 可空。 */
    public record SubmitReq(String customerName, String phoneMask, String level, String reason, String detail) {}

    /** 驳回/解除请求体：reason 必填（服务层校验非空）。 */
    public record ReasonReq(String reason) {}
}
