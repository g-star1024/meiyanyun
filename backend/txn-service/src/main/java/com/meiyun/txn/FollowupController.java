package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 随访端点（P5-B30 术后随访 SOP 引擎）：/api/txn/followup。全部经网关既有 txn 路由，免改网关。
 *
 * <p>读 followup:view、核销 followup:edit；数据域门店全见（FollowupService 统一 storeSpec）。
 * SOP 节点由治疗完成自动排程，本控制器不提供手工建随访端点（普通随访后续按需补 followup:create）。
 * /stats 供工作台两卡（待回访/超期）计数，替代前端全量拉取后 .length。</p>
 */
@RestController
@RequestMapping("/api/txn/followup")
public class FollowupController {

    private final FollowupService followupService;

    public FollowupController(FollowupService followupService) {
        this.followupService = followupService;
    }

    /** 随访分页列表：status/customerId/sopBatchId 精确过滤，sopOnly=true 只看术后 SOP 节点；排序后端固定。 */
    @GetMapping
    @RequirePerm("followup:view")
    public Page<FollowupView> list(@RequestParam(required = false) String storeCode,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String customerId,
                                   @RequestParam(required = false) String sopBatchId,
                                   @RequestParam(required = false) Boolean sopOnly,
                                   @PageableDefault(size = 20) Pageable pageable) {
        return followupService.page(storeCode, status, customerId, sopBatchId, sopOnly, pageable)
                .map(FollowupController::toView);
    }

    /** 本店术后 SOP 计数：sopPending/sopOverdue，供工作台待回访与超期两卡。 */
    @GetMapping("/stats")
    @RequirePerm("followup:view")
    public Map<String, Long> stats(@RequestParam(required = false) String storeCode) {
        return followupService.stats(storeCode);
    }

    @GetMapping("/{id}")
    @RequirePerm("followup:view")
    public FollowupView get(@PathVariable Long id) {
        return toView(followupService.get(id));
    }

    /** 登记回访结果（PENDING → DONE）。 */
    @PostMapping("/{id}/complete")
    @RequirePerm("followup:edit")
    public FollowupView complete(@PathVariable Long id, @RequestBody @Valid CompleteReq req) {
        return toView(followupService.complete(id, new FollowupService.CompleteCmd(
                req.satisfaction(), req.recovery(), req.adverseReaction(),
                req.adverseNote(), req.needRevisit(), req.note(), req.method())));
    }

    /** 标记无需回访（PENDING → SKIPPED），原因必填。 */
    @PostMapping("/{id}/skip")
    @RequirePerm("followup:edit")
    public FollowupView skip(@PathVariable Long id, @RequestBody @Valid SkipReq req) {
        return toView(followupService.skip(id, req.reason()));
    }

    private static FollowupView toView(Followup f) {
        return new FollowupView(
                String.valueOf(f.getId()), f.getFollowupNo(), f.getCustomerId(), f.getCustomerName(),
                f.getStoreCode(), f.getProject(), f.getRelatedOrderNo(),
                f.getServiceDate(), f.getPlanDate(), f.getMethod(), f.getStatus(),
                f.getSopStage(), f.getSopLabel(), f.getSopBatchId(), f.isEscalated(),
                f.getSatisfaction(), f.getRecovery(), f.isAdverseReaction(), f.getAdverseNote(),
                f.isNeedRevisit(), f.getNote(), f.getFollowupByName(), f.getDoneAt(), f.getCreatedAt());
    }

    /** 随访读模型（id 输出为字符串，对齐前端 Followup.id 字符串契约）。 */
    public record FollowupView(
            String id, String followupNo, String customerId, String customerName, String storeCode,
            String project, String relatedOrderNo,
            LocalDate serviceDate, LocalDate planDate, String method, String status,
            String sopStage, String sopLabel, String sopBatchId, boolean escalated,
            Integer satisfaction, String recovery, boolean adverseReaction, String adverseNote,
            boolean needRevisit, String note, String followupByName,
            OffsetDateTime doneAt, OffsetDateTime createdAt) {}

    public record CompleteReq(
            Integer satisfaction, String recovery, Boolean adverseReaction,
            String adverseNote, Boolean needRevisit, String note, String method) {}

    public record SkipReq(@NotBlank(message = "请填写无需回访的原因") String reason) {}
}
