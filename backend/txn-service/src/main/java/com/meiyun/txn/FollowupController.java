package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * 随访端点（P5-B30 术后随访 SOP 引擎 / B31 随访工作台接真）：/api/txn/followup。全部经网关既有 txn 路由，免改网关。
 *
 * <p>读 followup:view、手工建随访 followup:create、核销 followup:edit；数据域门店全见（FollowupService 统一 storeSpec）。
 * SOP 节点由治疗完成自动排程；POST 根路径供随访工作台手工建普通随访（MANUAL）。
 * /stats 返回工作台两卡 + 台账 KPI/角标计数（九键），替代前端全量拉取后 .length。</p>
 */
@RestController
@RequestMapping("/api/txn/followup")
public class FollowupController {

    private final FollowupService followupService;

    public FollowupController(FollowupService followupService) {
        this.followupService = followupService;
    }

    /** 随访分页列表：status/customerId/sopBatchId 精确过滤，sopOnly=true 只看术后 SOP 节点，keyword 模糊；排序后端固定。 */
    @GetMapping
    @RequirePerm("followup:view")
    public Page<FollowupView> list(@RequestParam(required = false) String storeCode,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String customerId,
                                   @RequestParam(required = false) String sopBatchId,
                                   @RequestParam(required = false) Boolean sopOnly,
                                   @RequestParam(required = false) String keyword,
                                   @PageableDefault(size = 20) Pageable pageable) {
        return followupService.page(storeCode, status, customerId, sopBatchId, sopOnly, keyword, pageable)
                .map(FollowupController::toView);
    }

    /** 本店随访计数九键：sopPending/sopOverdue 工作台卡片 + pending/todayPending/overdue/done/skipped/avgSatisfaction/adverseCount 台账 KPI。 */
    @GetMapping("/stats")
    @RequirePerm("followup:view")
    public Map<String, Object> stats(@RequestParam(required = false) String storeCode) {
        return followupService.stats(storeCode);
    }

    /** 手工建普通随访（工作台「新建回访计划」）：门店取 JWT，客户须已建档；sopStage=MANUAL。 */
    @PostMapping
    @RequirePerm("followup:create")
    public FollowupView create(@RequestBody @Valid CreateReq req) {
        return toView(followupService.create(new FollowupService.CreateCmd(
                req.customerId(), req.project(), req.relatedOrderNo(),
                req.serviceDate(), req.planDate(), req.method())));
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

    /** 实体 → 24 字段读模型（B31 SOP 批次节点聚合复用，id 字符串对齐前端契约）。 */
    public static FollowupView toView(Followup f) {
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

    /** 手工建普通随访请求：客户号/项目/服务日期/计划回访日期必填；关联订单号、方式可空（方式后端默认电话）。 */
    public record CreateReq(
            @NotBlank(message = "请选择客户") String customerId,
            @NotBlank(message = "请填写回访项目") String project,
            String relatedOrderNo,
            @NotNull(message = "请选择服务日期") LocalDate serviceDate,
            @NotNull(message = "请选择计划回访日期") LocalDate planDate,
            String method) {}

    public record CompleteReq(
            Integer satisfaction, String recovery, Boolean adverseReaction,
            String adverseNote, Boolean needRevisit, String note, String method) {}

    public record SkipReq(@NotBlank(message = "请填写无需回访的原因") String reason) {}
}
