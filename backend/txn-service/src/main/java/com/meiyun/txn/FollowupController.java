package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 随访端点（P5-B30 术后随访 SOP 引擎 / B31 随访工作台接真）：/api/txn/followup。全部经网关既有 txn 路由，免改网关。
 *
 * <p>读 followup:view、手工建随访 followup:create、核销 followup:edit；数据域门店全见（FollowupService 统一 storeSpec）。
 * SOP 节点由治疗完成自动排程；POST 根路径供随访工作台手工建普通随访（MANUAL）。
 * /stats 返回工作台两卡 + 台账 KPI/角标计数（九键），替代前端全量拉取后 .length。</p>
 *
 * <p>P6-B101 随访结构化分析：GET /stats/trend 满意度/不良反应分桶趋势（默认近 30 天按天，最长 366 天）；
 * 列表加 adverseOnly/adverseStatus 过滤；POST /{id}/adverse-handle 不良反应处置台
 * （仅已登记不良反应可处置，RESOLVED 必填说明，同状态同备注幂等）。</p>
 */
@RestController
@RequestMapping("/api/txn/followup")
public class FollowupController {

    private final FollowupService followupService;

    public FollowupController(FollowupService followupService) {
        this.followupService = followupService;
    }

    /** 随访分页列表：status/customerId/sopBatchId 精确过滤，sopOnly=true 只看术后 SOP 节点，
     *  adverseOnly=true 只看不良反应、adverseStatus 按处置状态过滤（P6-B101 处置台），keyword 模糊；排序后端固定。 */
    @GetMapping
    @RequirePerm("followup:view")
    public Page<FollowupView> list(@RequestParam(required = false) String storeCode,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String customerId,
                                   @RequestParam(required = false) String sopBatchId,
                                   @RequestParam(required = false) Boolean sopOnly,
                                   @RequestParam(required = false) Boolean adverseOnly,
                                   @RequestParam(required = false) String adverseStatus,
                                   @RequestParam(required = false) String keyword,
                                   @PageableDefault(size = 20) Pageable pageable) {
        return followupService.page(storeCode, status, customerId, sopBatchId, sopOnly,
                        adverseOnly, adverseStatus, keyword, pageable)
                .map(FollowupController::toView);
    }

    /** 本店随访计数九键：sopPending/sopOverdue 工作台卡片 + pending/todayPending/overdue/done/skipped/avgSatisfaction/adverseCount 台账 KPI。 */
    @GetMapping("/stats")
    @RequirePerm("followup:view")
    public Map<String, Object> stats(@RequestParam(required = false) String storeCode) {
        return followupService.stats(storeCode);
    }

    /** 满意度趋势（P6-B101）：按 done_at 分桶聚合 doneCount/avgSatisfaction/adverseCount 稠密序列；
     *  from/to 缺省近 30 天、最长 366 天，granularity=day|week（周以周一为桶首）。 */
    @GetMapping("/stats/trend")
    @RequirePerm("followup:view")
    public List<TrendPointView> trend(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String granularity) {
        return followupService.trend(storeCode, from, to, granularity).stream()
                .map(p -> new TrendPointView(p.bucket(), p.doneCount(), p.avgSatisfaction(), p.adverseCount()))
                .toList();
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

    /** 不良反应处置（P6-B101 处置台）：仅已登记不良反应的随访可处置（否则 400）；
     *  status 白名单 OPEN/PROCESSING/RESOLVED，RESOLVED 必填处置说明；同状态同备注幂等。 */
    @PostMapping("/{id}/adverse-handle")
    @RequirePerm("followup:edit")
    public FollowupView adverseHandle(@PathVariable Long id, @RequestBody AdverseHandleReq req) {
        return toView(followupService.adverseHandle(id,
                req == null ? null : req.status(), req == null ? null : req.note()));
    }

    /** 实体 → 28 字段读模型（B31 SOP 批次节点聚合复用，id 字符串对齐前端契约）。 */
    public static FollowupView toView(Followup f) {
        return new FollowupView(
                String.valueOf(f.getId()), f.getFollowupNo(), f.getCustomerId(), f.getCustomerName(),
                f.getStoreCode(), f.getProject(), f.getRelatedOrderNo(),
                f.getServiceDate(), f.getPlanDate(), f.getMethod(), f.getStatus(),
                f.getSopStage(), f.getSopLabel(), f.getSopBatchId(), f.isEscalated(),
                f.getSatisfaction(), f.getRecovery(), f.isAdverseReaction(), f.getAdverseNote(),
                f.getAdverseStatus(), f.getAdverseHandleNote(), f.getAdverseHandleBy(), f.getAdverseHandleAt(),
                f.isNeedRevisit(), f.getNote(), f.getFollowupByName(), f.getDoneAt(), f.getCreatedAt());
    }

    /** 随访读模型（id 输出为字符串，对齐前端 Followup.id 字符串契约）。 */
    public record FollowupView(
            String id, String followupNo, String customerId, String customerName, String storeCode,
            String project, String relatedOrderNo,
            LocalDate serviceDate, LocalDate planDate, String method, String status,
            String sopStage, String sopLabel, String sopBatchId, boolean escalated,
            Integer satisfaction, String recovery, boolean adverseReaction, String adverseNote,
            String adverseStatus, String adverseHandleNote, String adverseHandleBy, OffsetDateTime adverseHandleAt,
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

    /** 趋势点读模型（P6-B101）：分桶起始日 + 已回访数/平均满意度/不良反应数。 */
    public record TrendPointView(LocalDate bucket, long doneCount, BigDecimal avgSatisfaction, long adverseCount) {}

    /** 不良反应处置请求：status 必填（OPEN/PROCESSING/RESOLVED），note 处置说明（RESOLVED 必填）。 */
    public record AdverseHandleReq(String status, String note) {}
}
