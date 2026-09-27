package com.meiyun.customer;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * M3-B8 客诉管理与闭环（DESIGN-M3 §3 L90）。
 * 五态流转逐字 mock：PENDING_ACCEPT→PROCESSING/REJECTED→PENDING_REVIEW→CLOSED/退回/驳回；
 * 赔付 tierFor 服务端统算；complaint_log 逐步留痕；audit COMPLAINT 全动作。
 */
@RestController
@RequestMapping("/api/customer/m3/complaint")
public class ComplaintController {

    private final ComplaintService complaintService;

    public ComplaintController(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    /** 客诉列表（可选状态/仅医疗风险过滤） */
    @GetMapping("/records")
    @RequirePerm("complaint:view")
    public List<ComplaintService.ComplaintView> records(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) Boolean medicalOnly) {
        return complaintService.list(status, medicalOnly);
    }

    /** 登记客诉（actor 服务端取·storeCode 服务端写当前店） */
    @PostMapping("/records")
    @RequirePerm("complaint:create")
    public ComplaintService.ComplaintView create(@RequestBody CreateReq req) {
        return complaintService.create(req.customerId(), req.customerName(), req.source(), req.severity(),
                req.category(), req.medicalRisk(), req.description(), req.relatedOrderNo(),
                req.compensationAmount());
    }

    /** 受理（PENDING_ACCEPT → PROCESSING） */
    @PostMapping("/{id}/accept")
    @RequirePerm("complaint:edit")
    public ComplaintService.ComplaintView accept(@PathVariable Long id) {
        return complaintService.accept(id);
    }

    /** 提交处理方案（PROCESSING → PENDING_REVIEW·赔付变更重算签署层级） */
    @PostMapping("/{id}/submit-resolution")
    @RequirePerm("complaint:edit")
    public ComplaintService.ComplaintView submitResolution(@PathVariable Long id, @RequestBody SubmitReq req) {
        return complaintService.submitResolution(id, req.resolution(), req.compensationAmount());
    }

    /** 审批结案（PENDING_REVIEW → CLOSED） */
    @PostMapping("/{id}/approve-close")
    @RequirePerm("complaint:approve")
    public ComplaintService.ComplaintView approveClose(@PathVariable Long id) {
        return complaintService.approveClose(id);
    }

    /** 退回补充处理（PENDING_REVIEW → PROCESSING） */
    @PostMapping("/{id}/send-back")
    @RequirePerm("complaint:approve")
    public ComplaintService.ComplaintView sendBack(@PathVariable Long id, @RequestBody NoteReq req) {
        return complaintService.sendBack(id, req.note());
    }

    /** 驳回投诉（非终态 → REJECTED） */
    @PostMapping("/{id}/reject")
    @RequirePerm("complaint:approve")
    public ComplaintService.ComplaintView reject(@PathVariable Long id, @RequestBody ReasonReq req) {
        return complaintService.reject(id, req.reason());
    }

    public record CreateReq(String customerId, String customerName, String source, String severity,
                            String category, Boolean medicalRisk, String description,
                            String relatedOrderNo, Double compensationAmount) {}

    public record SubmitReq(String resolution, Double compensationAmount) {}

    public record NoteReq(String note) {}

    public record ReasonReq(String reason) {}
}
