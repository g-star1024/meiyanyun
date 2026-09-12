package com.meiyun.ai.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiApproval;
import com.meiyun.ai.domain.AiApprovalRepository;
import com.meiyun.ai.domain.AiFeatureBinding;
import com.meiyun.ai.domain.AiFeatureBindingRepository;
import com.meiyun.ai.domain.AiModel;
import com.meiyun.ai.domain.AiModelRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

@Service
public class ApprovalService {

    private static final Set<String> TYPES = Set.of("PROVIDER", "MODEL", "BINDING");
    private static final int PAGE_MAX = 200;

    private final AiApprovalRepository approvalRepo;
    private final AiModelRepository modelRepo;
    private final AiFeatureBindingRepository bindingRepo;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public ApprovalService(AiApprovalRepository approvalRepo,
                           AiModelRepository modelRepo,
                           AiFeatureBindingRepository bindingRepo,
                           AuditRecorder audit) {
        this.approvalRepo = approvalRepo;
        this.modelRepo = modelRepo;
        this.bindingRepo = bindingRepo;
        this.audit = audit;
    }

    public record ApprovalView(Long approvalId, String approvalType, Long targetId, String content,
                               String applicant, OffsetDateTime appliedAt, String decidedBy,
                               OffsetDateTime decidedAt, String status, String opinion) {
    }

    public record ApplyCmd(String approvalType, Long targetId, String content) {
    }

    public record DecideCmd(boolean approved, String opinion) {
    }

    @Transactional(readOnly = true)
    public Page<ApprovalView> list(String status, int page, int size) {
        int s = Math.min(Math.max(size, 1), PAGE_MAX);
        Pageable pageable = PageRequest.of(Math.max(page, 0), s);
        String st = normalizeStatus(status);
        Page<AiApproval> result = st == null
                ? approvalRepo.findAllByOrderByApprovalIdDesc(pageable)
                : approvalRepo.findByStatusOrderByApprovalIdDesc(st, pageable);
        return result.map(this::toView);
    }

    /** 登记一条待审批（供应商/模型/绑定敏感变更前置申请，一期由配置页「提交审批」触发）。 */
    @Transactional
    public ApprovalView apply(ApplyCmd cmd, String actor) {
        if (cmd == null || cmd.approvalType() == null || !TYPES.contains(cmd.approvalType().trim().toUpperCase())) {
            throw badRequest("审批类型仅支持 PROVIDER/MODEL/BINDING");
        }
        if (cmd.content() == null || cmd.content().isBlank()) {
            throw badRequest("申请内容不能为空");
        }
        AiApproval a = new AiApproval();
        a.setApprovalType(cmd.approvalType().trim().toUpperCase());
        a.setTargetId(cmd.targetId());
        a.setContent(cmd.content().trim());
        a.setApplicant(actor);
        a.setStatus("PENDING");
        AiApproval saved = approvalRepo.save(a);
        audit.record("AI_APPROVAL", "APR-" + saved.getApprovalId(), actor, "APPLY",
                payload(Map.of("type", saved.getApprovalType(),
                        "targetId", saved.getTargetId() == null ? "" : saved.getTargetId(),
                        "content", saved.getContent())));
        return toView(saved);
    }

    /** 审批决策：通过联动启用模型/绑定；驳回仅记录。重复决策中文拒绝。 */
    @Transactional
    public ApprovalView decide(Long id, DecideCmd cmd, String actor) {
        if (cmd == null) {
            throw badRequest("请求体不能为空");
        }
        AiApproval a = approvalRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "审批单不存在（id=" + id + "）"));
        if (!"PENDING".equals(a.getStatus())) {
            throw badRequest("该审批单已处理，当前状态：" + statusLabel(a.getStatus()));
        }
        String result = cmd.approved() ? "APPROVED" : "REJECTED";
        a.setStatus(result);
        a.setDecidedBy(actor);
        a.setDecidedAt(OffsetDateTime.now());
        a.setOpinion(cmd.opinion() == null || cmd.opinion().isBlank() ? null : cmd.opinion().trim());

        String linkage = "NONE";
        if (cmd.approved()) {
            linkage = applyLinkage(a);
        }
        approvalRepo.save(a);
        audit.record("AI_APPROVAL", "APR-" + id, actor, cmd.approved() ? "APPROVE" : "REJECT",
                payload(Map.of("type", a.getApprovalType(),
                        "targetId", a.getTargetId() == null ? "" : a.getTargetId(),
                        "linkage", linkage,
                        "opinion", a.getOpinion() == null ? "" : a.getOpinion())));
        return toView(a);
    }

    private String applyLinkage(AiApproval a) {
        if (a.getTargetId() == null) {
            return "NONE";
        }
        if ("MODEL".equals(a.getApprovalType())) {
            AiModel m = modelRepo.findById(a.getTargetId()).orElse(null);
            if (m != null && !Boolean.TRUE.equals(m.getEnabled())) {
                m.setEnabled(true);
                modelRepo.save(m);
                return "MODEL_ENABLED";
            }
        } else if ("BINDING".equals(a.getApprovalType())) {
            AiFeatureBinding b = bindingRepo.findById(a.getTargetId()).orElse(null);
            if (b != null && !Boolean.TRUE.equals(b.getEnabled())) {
                b.setEnabled(true);
                b.setUpdatedBy(a.getDecidedBy());
                bindingRepo.save(b);
                return "BINDING_ENABLED";
            }
        }
        return "NONE";
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String s = status.trim().toUpperCase();
        return switch (s) {
            case "PENDING", "APPROVED", "REJECTED" -> s;
            case "ALL" -> null;
            default -> throw badRequest("审批状态仅支持 PENDING/APPROVED/REJECTED");
        };
    }

    private ApprovalView toView(AiApproval a) {
        return new ApprovalView(
                a.getApprovalId(),
                a.getApprovalType(),
                a.getTargetId(),
                a.getContent(),
                a.getApplicant(),
                a.getAppliedAt(),
                a.getDecidedBy(),
                a.getDecidedAt(),
                a.getStatus(),
                a.getOpinion());
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "APPROVED" -> "已通过";
            case "REJECTED" -> "已驳回";
            default -> status;
        };
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private String payload(Map<String, ?> data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
