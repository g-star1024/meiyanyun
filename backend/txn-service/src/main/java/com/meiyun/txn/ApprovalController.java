package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审批中心 REST（T3-01）：统一聚合八类双签业务待办。
 * 网关 /api/txn 前缀已路由至 txn-service，无需改网关。
 *
 * <p>M7 权限闸门：读动作统一 approval:view（矩阵中仅管理层持该码）；同意/驳回须持
 * refund:approve 或 cardcancel:approve（矩阵中店长/财务/超管持有，区域经理仅 view 不可审批），
 * 阶段签核资格（REVIEW 店长 / FINANCE 财务）与指派人校验在服务层 guardStageAssignee 完成，
 * 操作人一律取 JWT 登录人（请求体 actor 字段忽略）。
 */
@RestController
@RequestMapping("/api/txn/approval")
@RequirePerm("approval:view")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /** 待办列表：tab=todo（待处理）/ done（已办结）/ all（全部）；可叠加 bizType 过滤。 */
    @GetMapping
    public List<ApprovalTodo> list(@RequestParam(required = false) String tab,
                                   @RequestParam(required = false) String bizType) {
        return approvalService.list(tab, bizType);
    }

    @GetMapping("/{todoNo}")
    public ApprovalTodo detail(@PathVariable String todoNo) {
        return approvalService.find(todoNo);
    }

    /** 同意：REVIEW 一审通过推进财务；FINANCE 终审通过办结（回写退款/退卡状态机）。 */
    @PostMapping("/{todoNo}/approve")
    @RequirePerm({"refund:approve", "cardcancel:approve"})
    public ApprovalTodo approve(@PathVariable String todoNo,
                                @RequestBody(required = false) ApprovalService.ActionCmd cmd) {
        return approvalService.approve(todoNo,
                cmd == null ? new ApprovalService.ActionCmd(null, null) : cmd);
    }

    /** 驳回：任一阶段可驳回（comment 必填），回写退款/退卡 REJECTED。 */
    @PostMapping("/{todoNo}/reject")
    @RequirePerm({"refund:approve", "cardcancel:approve"})
    public ApprovalTodo reject(@PathVariable String todoNo, @RequestBody ApprovalService.ActionCmd cmd) {
        return approvalService.reject(todoNo, cmd);
    }

    /** 转交他人审批（to 必填）：与同意/驳回同档，仅审批岗可操作。 */
    @PostMapping("/{todoNo}/transfer")
    @RequirePerm({"refund:approve", "cardcancel:approve"})
    public ApprovalTodo transfer(@PathVariable String todoNo, @RequestBody ApprovalService.TransferCmd cmd) {
        return approvalService.transfer(todoNo, cmd);
    }

    /** 加签（who 必填，去重）：与同意/驳回同档，仅审批岗可操作。 */
    @PostMapping("/{todoNo}/add-signer")
    @RequirePerm({"refund:approve", "cardcancel:approve"})
    public ApprovalTodo addSigner(@PathVariable String todoNo, @RequestBody ApprovalService.AddSignerCmd cmd) {
        return approvalService.addSigner(todoNo, cmd);
    }

    /**
     * 耗材领用提交（B5）：明细 SKU 行存待办 payload，固定双签（店长一审 → 财务终审），
     * 终审通过回调库存域扣库并落 TK-MATERIAL 成本。方法级权限覆盖类级 approval:view
     * （矩阵既有 requisition:edit，申领岗可提交但不可浏览审批台）；操作人取 JWT 登录人。
     */
    @PostMapping("/requisition")
    @RequirePerm("requisition:edit")
    public ApprovalTodo submitRequisition(@RequestBody ApprovalService.RequisitionCmd cmd) {
        return approvalService.submitRequisition(cmd);
    }

    /**
     * 耗材报损提交（B5）：损失额（分）决定签署层级（&lt;¥5000 财务单签，≥¥5000 店长+财务双签），
     * 终审通过回调库存域扣 SCRAP 库存并落 TK-LOSS 成本。方法级权限 wastage:edit 覆盖类级。
     */
    @PostMapping("/loss-report")
    @RequirePerm("wastage:edit")
    public ApprovalTodo submitLossReport(@RequestBody ApprovalService.LossReportCmd cmd) {
        return approvalService.submitLossReport(cmd);
    }
}
