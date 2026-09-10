package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EMR 病历独立域服务（P5-B27）：新建病历 / 存草稿 / 电子签名 / 归档 / 修订 / 列表查询，
 * 以及方案单签首程病历、完成治疗的联动落库（{@link #recordSignedFromPlan}）。
 *
 * <p>铁律：门店取 {@link DataScope} 当前登录本店（不信入参）；数据域一律 storeSpec 门店全见
 * （不做 SELF 收缩，对齐 EmrView 三 tab「本店病历全见」活规格）；状态机非法流转中文 400，
 * 越权统一 404；全动作审计（bizType=EMR）。无 customerId 的自由建档名病历拒收（引导先建档）。
 */
@Service
public class EmrService {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SIGNED = "SIGNED";
    public static final String ST_ARCHIVED = "ARCHIVED";

    private static final Set<String> TYPES = Set.of("FIRST_VISIT", "FOLLOW_UP", "TREATMENT", "PROCEDURE");
    /** 据方案单新建/联动病历允许的方案状态（五态）。 */
    private static final Set<String> PLAN_LINK_STATUS =
            Set.of("APPROVED", "READY_PAY", "PAID", "TREATING", "DONE");
    private static final ZoneOffset BIZ_TZ = ZoneOffset.ofHours(8);

    private final EmrRecordRepository emrRepo;
    private final PlanRepository planRepo;
    private final EmrNoGenerator noGen;
    private final ApptRefNameResolver names;
    private final AuditRecorder audit;

    public EmrService(EmrRecordRepository emrRepo, PlanRepository planRepo, EmrNoGenerator noGen,
                      ApptRefNameResolver names, AuditRecorder audit) {
        this.emrRepo = emrRepo;
        this.planRepo = planRepo;
        this.noGen = noGen;
        this.names = names;
        this.audit = audit;
    }

    /** 方案单联动入参：字段均可空（透传落库，空则空）。 */
    public record PlanEmrInput(String type, String doctorId, String diagnosis, String treatment,
                               String chiefComplaint, String presentIllness, String pastHistory,
                               String allergy, String prescription, String relatedOrderNo,
                               OffsetDateTime signedAt) {}

    // ==================== 新建 / 草稿 / 签名 / 归档 / 修订 ====================

    /** 新建病历（DRAFT）。门店取 JWT 本店；customerId 必填且客户须存在；consultId 二次校验五态+客户一致。 */
    @Transactional
    public EmrRecord create(CreateCmd cmd) {
        String actor = DataScope.currentActor();
        LoginUser user = DataScope.current();
        String storeCode = user == null ? null : user.storeCode();
        if (storeCode == null || storeCode.isBlank()) {
            throw badRequest("当前登录人未归属门店，无法新建病历");
        }
        if (blank(cmd.customerId())) {
            throw badRequest("请先到客情完成客户建档后再新建病历（不接收未落客户号的自由姓名单）");
        }
        String customerId = cmd.customerId().trim();
        String customerName = names.customerNames(List.of(customerId)).get(customerId);
        if (customerName == null) {
            throw badRequest("客户不存在: " + customerId + "（请先到客情建档）");
        }
        String type = defaultIfBlank(cmd.type(), "FIRST_VISIT");
        if (!TYPES.contains(type)) {
            throw badRequest("非法病历类型: " + type);
        }
        LocalDate visitDate = cmd.visitDate() != null ? cmd.visitDate() : LocalDate.now(BIZ_TZ);

        EmrRecord r = new EmrRecord();
        r.setEmrNo(noGen.nextEmrNo());
        r.setCustomerId(customerId);
        r.setCustomerName(customerName);
        r.setStoreCode(storeCode);
        r.setType(type);
        r.setVisitDate(visitDate);
        r.setStatus(ST_DRAFT);
        applyTextFields(r, cmd);
        r.setVersion(1);
        r.setCreatedBy(actor);
        if (!blank(cmd.consultId())) {
            linkPlan(r, cmd.consultId().trim(), customerId, storeCode);
        }
        EmrRecord saved = emrRepo.save(r);
        audit.record("EMR", saved.getEmrNo(), actor, "CREATE",
                "{\"customer\":\"" + esc(customerId) + "\",\"type\":\"" + type
                        + "\",\"consultId\":\"" + (saved.getConsultId() == null ? "" : esc(saved.getConsultId()))
                        + "\"}");
        return saved;
    }

    /** 保存草稿：仅 DRAFT 可覆盖，七项文本字段全量更新（空白置空）。 */
    @Transactional
    public EmrRecord saveDraft(String emrNo, DraftCmd cmd) {
        EmrRecord r = requireRecord(emrNo);
        if (!ST_DRAFT.equals(r.getStatus())) {
            throw badRequest("仅草稿状态的病历可编辑保存，当前状态: " + r.getStatus());
        }
        applyTextFields(r, cmd);
        r.setUpdatedAt(OffsetDateTime.now());
        EmrRecord saved = emrRepo.save(r);
        audit.record("EMR", emrNo, DataScope.currentActor(), "SAVE_DRAFT", "{}");
        return saved;
    }

    /** 电子签名：仅 DRAFT；诊断+治疗方案必填；盖当前 JWT 人（doctorId/doctorName/signedBy 一并落）。 */
    @Transactional
    public EmrRecord sign(String emrNo) {
        EmrRecord r = requireRecord(emrNo);
        if (!ST_DRAFT.equals(r.getStatus())) {
            throw badRequest("仅草稿状态的病历可签名，当前状态: " + r.getStatus());
        }
        if (blank(r.getDiagnosis()) || blank(r.getTreatment())) {
            throw badRequest("诊断与治疗方案为签名必填项，请补全后再签名");
        }
        String actor = DataScope.currentActor();
        String actorName = names.staffNames(List.of(actor)).getOrDefault(actor, actor);
        OffsetDateTime now = OffsetDateTime.now();
        r.setDoctorId(actor);
        r.setDoctorName(actorName);
        r.setSignedBy(actor);
        r.setSignedByName(actorName);
        r.setSignedAt(now);
        r.setStatus(ST_SIGNED);
        r.setUpdatedAt(now);
        EmrRecord saved = emrRepo.save(r);
        audit.record("EMR", emrNo, actor, "SIGN",
                "{\"doctor\":\"" + esc(actor) + "\",\"version\":" + r.getVersion() + "}");
        return saved;
    }

    /** 归档：SIGNED → ARCHIVED。 */
    @Transactional
    public EmrRecord archive(String emrNo) {
        EmrRecord r = requireRecord(emrNo);
        if (!ST_SIGNED.equals(r.getStatus())) {
            throw badRequest("仅已签名的病历可归档，当前状态: " + r.getStatus());
        }
        r.setStatus(ST_ARCHIVED);
        r.setUpdatedAt(OffsetDateTime.now());
        EmrRecord saved = emrRepo.save(r);
        audit.record("EMR", emrNo, DataScope.currentActor(), "ARCHIVE", "{}");
        return saved;
    }

    /**
     * 修订：源单须非 DRAFT（SIGNED/ARCHIVED）。复制源单内容生成 version+1 的新草稿，
     * 新 PK=源号-R{version+1}（不进查库序号池），清签名字段，parent_id 回溯源单。
     */
    @Transactional
    public EmrRecord revise(String emrNo) {
        EmrRecord src = requireRecord(emrNo);
        if (ST_DRAFT.equals(src.getStatus())) {
            throw badRequest("草稿状态无需修订，请直接编辑保存");
        }
        int newVersion = (src.getVersion() == null ? 1 : src.getVersion()) + 1;
        String newNo = src.getEmrNo() + "-R" + newVersion;
        if (emrRepo.existsById(newNo)) {
            throw badRequest("该病历的 R" + newVersion + " 修订单已存在，请刷新列表");
        }
        String actor = DataScope.currentActor();
        OffsetDateTime now = OffsetDateTime.now();

        EmrRecord r = new EmrRecord();
        r.setEmrNo(newNo);
        r.setCustomerId(src.getCustomerId());
        r.setCustomerName(src.getCustomerName());
        r.setStoreCode(src.getStoreCode());
        r.setType(src.getType());
        r.setVisitDate(src.getVisitDate());
        r.setStatus(ST_DRAFT);
        r.setChiefComplaint(src.getChiefComplaint());
        r.setPresentIllness(src.getPresentIllness());
        r.setPastHistory(src.getPastHistory());
        r.setAllergy(src.getAllergy());
        r.setDiagnosis(src.getDiagnosis());
        r.setTreatment(src.getTreatment());
        r.setPrescription(src.getPrescription());
        r.setDoctorId(null);
        r.setDoctorName(null);
        r.setRelatedAppointmentNo(src.getRelatedAppointmentNo());
        r.setRelatedOrderNo(src.getRelatedOrderNo());
        r.setConsultId(src.getConsultId());
        r.setVersion(newVersion);
        r.setParentId(src.getEmrNo());
        r.setCreatedBy(actor);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        EmrRecord saved = emrRepo.save(r);
        audit.record("EMR", newNo, actor, "REVISE",
                "{\"parent\":\"" + esc(src.getEmrNo()) + "\",\"version\":" + newVersion + "}");
        return saved;
    }

    // ==================== 方案单联动（M4 签首程 / 完成治疗） ====================

    /**
     * 方案单环节直接落 SIGNED 病历（FIRST_VISIT/TREATMENT）。consult_id+type 幂等：
     * 已存在则直接返回既有记录（重试/重复提交不双写、不换号）。
     */
    @Transactional
    public EmrRecord recordSignedFromPlan(ConsultPlan plan, PlanEmrInput in) {
        var exist = emrRepo.findFirstByConsultIdAndType(plan.getPlanId(), in.type());
        if (exist.isPresent()) {
            return exist.get();
        }
        String actor = DataScope.currentActor();
        OffsetDateTime signedAt = in.signedAt() != null ? in.signedAt() : OffsetDateTime.now();
        String doctorId = !blank(in.doctorId()) ? in.doctorId().trim() : actor;
        Map<String, String> staffNames = names.staffNames(List.of(doctorId, actor));
        String customerName = names.customerNames(List.of(plan.getCustomerId()))
                .getOrDefault(plan.getCustomerId(), plan.getCustomerId());

        EmrRecord r = new EmrRecord();
        r.setEmrNo(noGen.nextEmrNo());
        r.setCustomerId(plan.getCustomerId());
        r.setCustomerName(customerName);
        r.setStoreCode(plan.getStoreCode());
        r.setType(in.type());
        r.setVisitDate(signedAt.atZoneSameInstant(BIZ_TZ).toLocalDate());
        r.setStatus(ST_SIGNED);
        r.setChiefComplaint(trimToNull(in.chiefComplaint()));
        r.setPresentIllness(trimToNull(in.presentIllness()));
        r.setPastHistory(trimToNull(in.pastHistory()));
        r.setAllergy(trimToNull(in.allergy()));
        r.setDiagnosis(trimToNull(in.diagnosis()));
        r.setTreatment(trimToNull(in.treatment()));
        r.setPrescription(trimToNull(in.prescription()));
        r.setDoctorId(doctorId);
        r.setDoctorName(staffNames.getOrDefault(doctorId, doctorId));
        r.setRelatedOrderNo(trimToNull(in.relatedOrderNo()));
        r.setConsultId(plan.getPlanId());
        r.setVersion(1);
        r.setSignedBy(actor);
        r.setSignedByName(staffNames.getOrDefault(actor, actor));
        r.setSignedAt(signedAt);
        r.setCreatedBy(actor);
        EmrRecord saved = emrRepo.save(r);
        audit.record("EMR", saved.getEmrNo(), actor, "SIGN",
                "{\"fromPlan\":\"" + esc(plan.getPlanId()) + "\",\"type\":\"" + in.type()
                        + "\",\"doctor\":\"" + esc(doctorId) + "\"}");
        return saved;
    }

    // ==================== 查询（真分页 / 状态聚合） ====================

    /**
     * 病历分页列表：storeSpec 门店全见；显式他店码 404；可按状态/客户/方案单/关键字叠加过滤。
     * 排序固定 visitDate、createdAt 双倒序（不信入参排序，防任意字段排序）。
     */
    @Transactional(readOnly = true)
    public Page<EmrRecord> page(String storeCode, String status, String customerId, String consultId,
                                String keyword, Pageable pageable) {
        Specification<EmrRecord> spec = listSpec(storeCode, status, customerId, consultId, keyword);
        PageRequest page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("visitDate"), Sort.Order.desc("createdAt")));
        return emrRepo.findAll(spec, page);
    }

    /**
     * 本店病历计数聚合（工作台 KPI / 三 tab 角标，前端不再全量拉取后 .length）：
     * draft/signed/archived 三态计数 + signedThisMonth 本月新签名数；locked=signed+archived 由前端相加。
     */
    @Transactional(readOnly = true)
    public Map<String, Long> stats(String storeCode) {
        if (!blank(storeCode) && !DataScope.canReadStore(storeCode)) {
            throw notFound("数据不存在或无权查看");
        }
        Specification<EmrRecord> base = DataScope.storeSpec("storeCode");
        if (!blank(storeCode)) {
            base = base.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("draft", emrRepo.count(base.and(eqStatus(ST_DRAFT))));
        out.put("signed", emrRepo.count(base.and(eqStatus(ST_SIGNED))));
        out.put("archived", emrRepo.count(base.and(eqStatus(ST_ARCHIVED))));
        OffsetDateTime monthStart = LocalDate.now(BIZ_TZ).withDayOfMonth(1).atStartOfDay(BIZ_TZ).toOffsetDateTime();
        Specification<EmrRecord> monthSigned = base
                .and(eqStatus(ST_SIGNED))
                .and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("signedAt"), monthStart));
        out.put("signedThisMonth", emrRepo.count(monthSigned));
        return out;
    }

    /** 列表/计数共用过滤规格：状态/客户/方案单精确匹配，关键字对姓名/病历号/诊断/主诉 OR LIKE。 */
    private Specification<EmrRecord> listSpec(String storeCode, String status, String customerId,
                                              String consultId, String keyword) {
        if (!blank(storeCode) && !DataScope.canReadStore(storeCode)) {
            throw notFound("数据不存在或无权查看");
        }
        Specification<EmrRecord> spec = DataScope.storeSpec("storeCode");
        if (!blank(storeCode)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        if (!blank(status)) {
            spec = spec.and(eqStatus(status.trim()));
        }
        if (!blank(customerId)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("customerId"), customerId.trim()));
        }
        if (!blank(consultId)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("consultId"), consultId.trim()));
        }
        if (!blank(keyword)) {
            String like = "%" + keyword.trim().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("customerName")), like),
                    cb.like(cb.lower(root.get("emrNo")), like),
                    cb.like(cb.lower(root.get("diagnosis")), like),
                    cb.like(cb.lower(root.get("chiefComplaint")), like)));
        }
        return spec;
    }

    private static Specification<EmrRecord> eqStatus(String status) {
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    @Transactional(readOnly = true)
    public EmrRecord get(String emrNo) {
        return requireRecord(emrNo);
    }

    // ==================== 内部 ====================

    /** 据方案单建病历时的方案校验：五态 + 同客户 + 本店可见。 */
    private void linkPlan(EmrRecord r, String planId, String customerId, String storeCode) {
        ConsultPlan p = planRepo.findById(planId).orElse(null);
        if (p == null || !DataScope.canReadStore(p.getStoreCode())) {
            throw badRequest("关联方案单不存在: " + planId);
        }
        if (!PLAN_LINK_STATUS.contains(p.getStatus())) {
            throw badRequest("方案单当前状态不允许新建病历: " + p.getStatus()
                    + "（须审核通过及以后状态）");
        }
        if (!customerId.equals(p.getCustomerId())) {
            throw badRequest("病历客户与方案单客户不一致，禁止跨客户关联");
        }
        if (!storeCode.equals(p.getStoreCode())) {
            throw badRequest("病历门店与方案单门店不一致，禁止跨店关联");
        }
        r.setConsultId(planId);
    }

    /** 七项文本字段全量覆盖（Cmd 统一为七字段形状；create 额外的 customerId/type 等不经此方法）。 */
    private void applyTextFields(EmrRecord r, TextCmd cmd) {
        r.setChiefComplaint(trimToNull(cmd.chiefComplaint()));
        r.setPresentIllness(trimToNull(cmd.presentIllness()));
        r.setPastHistory(trimToNull(cmd.pastHistory()));
        r.setAllergy(trimToNull(cmd.allergy()));
        r.setDiagnosis(trimToNull(cmd.diagnosis()));
        r.setTreatment(trimToNull(cmd.treatment()));
        r.setPrescription(trimToNull(cmd.prescription()));
    }

    private EmrRecord requireRecord(String emrNo) {
        EmrRecord r = emrRepo.findById(emrNo)
                .orElseThrow(() -> notFound("数据不存在或无权查看"));
        if (!DataScope.canReadStore(r.getStoreCode())) {
            throw notFound("数据不存在或无权查看");
        }
        return r;
    }

    /** 新建病历入参：customerId 必填；customerName 仅展示参考（后端以客户服务解析为准，不信入参名）。 */
    public record CreateCmd(String customerId, String customerName, String type, LocalDate visitDate,
                            String chiefComplaint, String presentIllness, String pastHistory,
                            String allergy, String diagnosis, String treatment, String prescription,
                            String relatedOrderNo, String consultId) implements TextCmd {}

    /** 存草稿入参（七项文本）。 */
    public record DraftCmd(String chiefComplaint, String presentIllness, String pastHistory,
                           String allergy, String diagnosis, String treatment,
                           String prescription) implements TextCmd {}

    /** 七项文本字段共同形状。 */
    private interface TextCmd {
        String chiefComplaint();
        String presentIllness();
        String pastHistory();
        String allergy();
        String diagnosis();
        String treatment();
        String prescription();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String defaultIfBlank(String s, String def) {
        return s == null || s.isBlank() ? def : s.trim();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
