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
 * EMR 病历端点（P5-B27 / P5-B30）：/api/txn/emr。全部经网关既有 txn 路由，免改网关。
 *
 * <p>读 emr:view、建 emr:create、写 emr:edit；数据域门店全见（EmrService 统一 storeSpec）。
 * P5-B30：列表真分页（后端固定排序，不信入参 sort）+ /stats 状态计数聚合 + /templates 模板库。
 */
@RestController
@RequestMapping("/api/txn/emr")
public class EmrController {

    private final EmrService emrService;
    private final EmrTemplateService templateService;

    public EmrController(EmrService emrService, EmrTemplateService templateService) {
        this.emrService = emrService;
        this.templateService = templateService;
    }

    /** 病历分页列表：status/customerId/consultId 精确过滤，q 模糊客户名/病历号/诊断/主诉；排序后端固定。 */
    @GetMapping
    @RequirePerm("emr:view")
    public Page<EmrView> list(@RequestParam(required = false) String storeCode,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String customerId,
                              @RequestParam(required = false) String consultId,
                              @RequestParam(required = false) String q,
                              @PageableDefault(size = 20) Pageable pageable) {
        return emrService.page(storeCode, status, customerId, consultId, q, pageable)
                .map(EmrController::toView);
    }

    /** 本店病历计数：draft/signed/archived/signedThisMonth，供 tab 角标与 KPI（替代前端全量 .length）。 */
    @GetMapping("/stats")
    @RequirePerm("emr:view")
    public Map<String, Long> stats(@RequestParam(required = false) String storeCode) {
        return emrService.stats(storeCode);
    }

    @GetMapping("/{emrNo}")
    @RequirePerm("emr:view")
    public EmrView get(@PathVariable String emrNo) {
        return toView(emrService.get(emrNo));
    }

    @PostMapping
    @RequirePerm("emr:create")
    public EmrView create(@RequestBody @Valid CreateReq req) {
        return toView(emrService.create(new EmrService.CreateCmd(
                req.customerId(), req.customerName(), req.type(), req.visitDate(),
                req.chiefComplaint(), req.presentIllness(), req.pastHistory(),
                req.allergy(), req.diagnosis(), req.treatment(), req.prescription(),
                req.relatedOrderNo(), req.consultId())));
    }

    @PostMapping("/{emrNo}/draft")
    @RequirePerm("emr:edit")
    public EmrView saveDraft(@PathVariable String emrNo, @RequestBody DraftReq req) {
        return toView(emrService.saveDraft(emrNo, new EmrService.DraftCmd(
                req.chiefComplaint(), req.presentIllness(), req.pastHistory(),
                req.allergy(), req.diagnosis(), req.treatment(), req.prescription())));
    }

    @PostMapping("/{emrNo}/sign")
    @RequirePerm("emr:edit")
    public EmrView sign(@PathVariable String emrNo) {
        return toView(emrService.sign(emrNo));
    }

    @PostMapping("/{emrNo}/archive")
    @RequirePerm("emr:edit")
    public EmrView archive(@PathVariable String emrNo) {
        return toView(emrService.archive(emrNo));
    }

    @PostMapping("/{emrNo}/revise")
    @RequirePerm("emr:create")
    public EmrView revise(@PathVariable String emrNo) {
        return toView(emrService.revise(emrNo));
    }

    // ==================== 病历模板库（P5-B30） ====================

    /** 套用候选：集团通用 + 本店自建；type 可选下推数据库过滤。 */
    @GetMapping("/templates")
    @RequirePerm("emr:view")
    public Page<EmrTemplate> templates(@RequestParam(required = false) String type,
                                       @PageableDefault(size = 50) Pageable pageable) {
        return templateService.listActive(type, pageable);
    }

    /** 门店自建模板（自动盖本店码；复用 emr:create，不新增权限码）。 */
    @PostMapping("/templates")
    @RequirePerm("emr:create")
    public EmrTemplate createTemplate(@RequestBody @Valid TemplateReq req) {
        return templateService.create(new EmrTemplateService.CreateCmd(
                req.name(), req.type(), req.chiefComplaint(), req.presentIllness(),
                req.pastHistory(), req.allergy(), req.diagnosis(), req.treatment(),
                req.prescription()));
    }

    /** 停用本店自建模板（集团模板只读，停用返回 404）。 */
    @PostMapping("/templates/{templateNo}/disable")
    @RequirePerm("emr:create")
    public EmrTemplate disableTemplate(@PathVariable String templateNo) {
        return templateService.disable(templateNo);
    }

    private static EmrView toView(EmrRecord r) {
        return new EmrView(
                r.getEmrNo(), r.getEmrNo(), r.getCustomerId(), r.getCustomerName(),
                r.getStoreCode(), r.getType(), r.getVisitDate(), r.getStatus(),
                r.getChiefComplaint(), r.getPresentIllness(), r.getPastHistory(),
                r.getAllergy(), r.getDiagnosis(), r.getTreatment(), r.getPrescription(),
                r.getDoctorId(), r.getDoctorName(),
                r.getRelatedAppointmentNo(), r.getRelatedOrderNo(), r.getConsultId(),
                r.getVersion(), r.getParentId(),
                r.getSignedBy(), r.getSignedByName(), r.getSignedAt(),
                r.getCreatedBy(), r.getCreatedAt(), r.getUpdatedAt());
    }

    /** 病历读模型（id 与 emrNo 同值，对齐前端 id 主键契约）。 */
    public record EmrView(
            String id, String emrNo, String customerId, String customerName, String storeCode,
            String type, LocalDate visitDate, String status,
            String chiefComplaint, String presentIllness, String pastHistory,
            String allergy, String diagnosis, String treatment, String prescription,
            String doctorId, String doctorName,
            String relatedAppointmentNo, String relatedOrderNo, String consultId,
            Integer version, String parentId,
            String signedBy, String signedByName, OffsetDateTime signedAt,
            String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record CreateReq(
            @NotBlank(message = "客户不能为空（请先在客情建档）") String customerId,
            String customerName, String type, LocalDate visitDate,
            String chiefComplaint, String presentIllness, String pastHistory,
            String allergy, String diagnosis, String treatment, String prescription,
            String relatedOrderNo, String consultId) {}

    public record DraftReq(String chiefComplaint, String presentIllness, String pastHistory,
                           String allergy, String diagnosis, String treatment,
                           String prescription) {}

    public record TemplateReq(
            @NotBlank(message = "模板名称不能为空") String name,
            String type,
            String chiefComplaint, String presentIllness, String pastHistory,
            String allergy, String diagnosis, String treatment, String prescription) {}
}
