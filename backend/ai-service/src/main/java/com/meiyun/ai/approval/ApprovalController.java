package com.meiyun.ai.approval;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 治理审批（A1Govern 审批 Tab）。列表 aiGovern:view，决策 aiGovern:approve。
 */
@RestController
@RequestMapping("/api/ai/approvals")
@RequirePerm("aiGovern:view")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public Page<ApprovalService.ApprovalView> list(@RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return approvalService.list(status, page, size);
    }

    @PostMapping
    @RequirePerm("aiAdmin:edit")
    public ApprovalService.ApprovalView apply(@RequestBody ApprovalService.ApplyCmd cmd) {
        return approvalService.apply(cmd, DataScope.currentActor());
    }

    @PostMapping("/{id}/decide")
    @RequirePerm("aiGovern:approve")
    public ApprovalService.ApprovalView decide(@PathVariable Long id,
                                                @RequestBody ApprovalService.DecideCmd cmd) {
        return approvalService.decide(id, cmd, DataScope.currentActor());
    }
}
