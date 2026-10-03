package com.meiyun.customer;

import com.meiyun.customer.CustomerService.BadReq;
import com.meiyun.customer.CustomerService.Conflict;
import com.meiyun.customer.CustomerService.NotFound;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.customer.client.MarketingFollowTaskClient;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客诉服务（M3-B8 / DESIGN-M3 §3 M3-20）。
 * 状态机照前端 mock 活规格逐字：PENDING_ACCEPT→[PROCESSING,REJECTED]；
 * PROCESSING→[PENDING_REVIEW,REJECTED]；PENDING_REVIEW→[CLOSED,PROCESSING,REJECTED]；
 * CLOSED/REJECTED 终态空（违反→409 中文透出当前态）。
 * 赔付金额 API 层元、DB 层分（BIGINT）；签署层级服务端统算：>=20000 元→L3 / >=5000 元→L2 / 否则 L1
 * （与前端 config/settings.ts DEFAULT dualSign 同源，create 与 submitResolution 赔付变更时重算）。
 * 时间线落 complaint_log 独立表（by→by_name / at→at_time 列名映射回前端契约）；
 * 全程落 COMPLAINT 审计（CREATE/ACCEPT/SUBMIT/APPROVE_CLOSE/SEND_BACK/REJECT），
 * 操作人取 DataScope.currentActor()（请求体不收 actor，防伪造）；
 * m3_settings.complaintAutoTask=1（缺省 true）时 create/accept 经 MarketingFollowTaskClient
 * 下发跟进任务（source="COMPLAINT"，idemKey=complaint-{phase}-{id}，软降级不阻断）。
 */
@Service
public class ComplaintService {

    /** 状态机迁移表（与前端 stores/complaint.ts TRANSITIONS 逐字一致）。 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            Complaint.STATUS_PENDING_ACCEPT, Set.of(Complaint.STATUS_PROCESSING, Complaint.STATUS_REJECTED),
            Complaint.STATUS_PROCESSING, Set.of(Complaint.STATUS_PENDING_REVIEW, Complaint.STATUS_REJECTED),
            Complaint.STATUS_PENDING_REVIEW, Set.of(Complaint.STATUS_CLOSED, Complaint.STATUS_PROCESSING,
                    Complaint.STATUS_REJECTED),
            Complaint.STATUS_CLOSED, Set.of(),
            Complaint.STATUS_REJECTED, Set.of());

    private static final Set<String> SOURCES = Set.of(
            Complaint.SOURCE_STORE, Complaint.SOURCE_PHONE, Complaint.SOURCE_ONLINE,
            Complaint.SOURCE_THIRD_PARTY);
    private static final Set<String> SEVERITIES = Set.of(
            Complaint.SEVERITY_LOW, Complaint.SEVERITY_MEDIUM, Complaint.SEVERITY_HIGH);
    private static final Set<String> CATEGORIES = Set.of(
            Complaint.CATEGORY_SERVICE, Complaint.CATEGORY_MEDICAL, Complaint.CATEGORY_BILLING,
            Complaint.CATEGORY_OUTCOME, Complaint.CATEGORY_OTHER);

    /** 签署层级阈值（元）：>=20000→L3 / >=5000→L2 / 否则 L1（与前端 dualSign l2/l3 同源）。 */
    private static final long TIER_L2_MIN_YUAN = 5000L;
    private static final long TIER_L3_MIN_YUAN = 20000L;

    private final ComplaintRepository complaintRepository;
    private final ComplaintLogRepository logRepository;
    private final AuditRecorder audit;
    private final M3SettingsService m3SettingsService;
    private final MarketingFollowTaskClient followTaskClient;
    private final RefNameResolver nameResolver;
    private final CustomerRepository customerRepository;

    public ComplaintService(ComplaintRepository complaintRepository, ComplaintLogRepository logRepository,
                            AuditRecorder audit, M3SettingsService m3SettingsService,
                            MarketingFollowTaskClient followTaskClient, RefNameResolver nameResolver,
                            CustomerRepository customerRepository) {
        this.complaintRepository = complaintRepository;
        this.logRepository = logRepository;
        this.audit = audit;
        this.m3SettingsService = m3SettingsService;
        this.followTaskClient = followTaskClient;
        this.nameResolver = nameResolver;
        this.customerRepository = customerRepository;
    }

    /** 时间线条目（与前端 {at,by,action,note?} 契约一致；note 为空序列化为 null）。 */
    public record TimelineItem(String at, String by, String action, String note) {}

    /** 客诉视图（字段名与前端 mock Complaint 契约逐一对齐；compensationAmount 单位为元）。 */
    public record ComplaintView(
            Long id, String complaintNo, String customerId, String customerName,
            String source, String severity, String category, Boolean medicalRisk,
            String description, String relatedOrderNo, String storeId, String storeName,
            String status, Double compensationAmount, String signTier, String resolution,
            String createdAt, String acceptedByName, String acceptedAt,
            String submittedByName, String submittedAt, String closedByName, String closedAt,
            String rejectionReason, List<TimelineItem> timeline) {}

    /** 列表：登记时刻倒序；status 精确过滤 / medicalOnly=true 仅医疗风险（与前端 filtered 口径一致）。 */
    @Transactional(readOnly = true)
    public List<ComplaintView> list(String status, Boolean medicalOnly) {
        List<Complaint> rows = complaintRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> status == null || status.isBlank() || status.equals(c.getStatus()))
                .filter(c -> medicalOnly == null || !medicalOnly || Boolean.TRUE.equals(c.getMedicalRisk()))
                .toList();
        Map<Long, List<TimelineItem>> logs = new java.util.HashMap<>();
        List<Long> ids = rows.stream().map(Complaint::getId).toList();
        if (!ids.isEmpty()) {
            for (ComplaintLog l : logRepository.findByComplaintIdInOrderByAtTimeAscIdAsc(ids)) {
                logs.computeIfAbsent(l.getComplaintId(), k -> new java.util.ArrayList<>())
                        .add(toTimelineItem(l));
            }
        }
        return rows.stream()
                .map(c -> toView(c, logs.getOrDefault(c.getId(), List.of())))
                .toList();
    }

    /** 登记投诉：客户编号非空＋customer 表存在性校验（纵深防御，拒 ''/'C-NEW' 硬编码透写）；描述/姓名必填＋词表校验；单号 TS+yyyyMMdd-3位；签署层级统算；落 CREATE 审计＋跟进联动。 */
    @Transactional
    public ComplaintView create(String customerId, String customerName, String source, String severity,
                                String category, Boolean medicalRisk, String description,
                                String relatedOrderNo, Double compensationYuan) {
        if (customerId == null || customerId.isBlank()) {
            throw new BadReq("客户编号必填，请检索选择真实客户");
        }
        if (!customerRepository.existsById(customerId.trim())) {
            throw new BadReq("客户不存在，请检索选择真实客户");
        }
        if (customerName == null || customerName.isBlank()) {
            throw new BadReq("客户姓名必填");
        }
        if (description == null || description.isBlank()) {
            throw new BadReq("投诉描述必填");
        }
        if (source == null || !SOURCES.contains(source)) {
            throw new BadReq("投诉来源不合法，仅支持 STORE/PHONE/ONLINE/THIRD_PARTY");
        }
        if (severity == null || !SEVERITIES.contains(severity)) {
            throw new BadReq("严重度不合法，仅支持 LOW/MEDIUM/HIGH");
        }
        if (category == null || !CATEGORIES.contains(category)) {
            throw new BadReq("投诉分类不合法");
        }
        long cents = compensationYuan == null || compensationYuan < 0
                ? 0L : Math.round(compensationYuan * 100);
        boolean med = Boolean.TRUE.equals(medicalRisk);
        String actor = DataScope.currentActor();
        OffsetDateTime now = OffsetDateTime.now();
        LoginUser u = DataScope.current();
        String storeCode = u == null ? null : u.storeCode();
        Complaint c = new Complaint();
        c.setComplaintNo(nextComplaintNo());
        c.setCustomerId(customerId.trim());
        c.setCustomerName(customerName.trim());
        c.setSource(source);
        c.setSeverity(severity);
        c.setCategory(category);
        c.setMedicalRisk(med);
        c.setDescription(description.trim());
        c.setRelatedOrderNo(relatedOrderNo == null || relatedOrderNo.isBlank() ? null : relatedOrderNo.trim());
        c.setStoreCode(storeCode);
        c.setStoreName(storeCode == null || storeCode.isBlank() ? null
                : nameResolver.storeNames(List.of(storeCode)).get(storeCode));
        c.setStatus(Complaint.STATUS_PENDING_ACCEPT);
        c.setCompensationAmountCents(cents);
        c.setSignTier(tierFor(cents));
        Complaint saved = complaintRepository.save(c);
        appendLog(saved.getId(), now, actor, "登记投诉", med ? "标记为医疗风险" : null);
        audit.record("COMPLAINT", saved.getComplaintNo(), actor, "CREATE",
                "{\"complaintNo\":\"" + esc(saved.getComplaintNo()) + "\",\"customer\":\""
                        + esc(saved.getCustomerName()) + "\",\"severity\":\"" + severity
                        + "\",\"medicalRisk\":" + med + "}");
        dispatchFollowTask(saved, "create");
        return toView(saved, timelineOf(saved.getId()));
    }

    /** 受理：待受理→处理中；记受理人/时刻；落 ACCEPT 审计＋跟进联动。 */
    @Transactional
    public ComplaintView accept(Long id) {
        Complaint c = mustGet(id);
        mustTransit(c, Complaint.STATUS_PROCESSING);
        String actor = DataScope.currentActor();
        OffsetDateTime now = OffsetDateTime.now();
        c.setStatus(Complaint.STATUS_PROCESSING);
        c.setAcceptedByName(actor);
        c.setAcceptedAt(now);
        Complaint saved = complaintRepository.save(c);
        appendLog(id, now, actor, "受理投诉", null);
        audit.record("COMPLAINT", saved.getComplaintNo(), actor, "ACCEPT",
                "{\"complaintNo\":\"" + esc(saved.getComplaintNo()) + "\"}");
        dispatchFollowTask(saved, "accept");
        return toView(saved, timelineOf(id));
    }

    /** 提交处理方案：处理中→待结案审批；方案必填；赔付金额变更时重算签署层级；落 SUBMIT 审计。 */
    @Transactional
    public ComplaintView submitResolution(Long id, String resolution, Double compensationYuan) {
        Complaint c = mustGet(id);
        mustTransit(c, Complaint.STATUS_PENDING_REVIEW);
        if (resolution == null || resolution.isBlank()) {
            throw new BadReq("处理方案必填");
        }
        if (compensationYuan != null && compensationYuan >= 0) {
            long cents = Math.round(compensationYuan * 100);
            c.setCompensationAmountCents(cents);
            c.setSignTier(tierFor(cents));
        }
        String actor = DataScope.currentActor();
        OffsetDateTime now = OffsetDateTime.now();
        c.setStatus(Complaint.STATUS_PENDING_REVIEW);
        c.setResolution(resolution.trim());
        c.setSubmittedByName(actor);
        c.setSubmittedAt(now);
        Complaint saved = complaintRepository.save(c);
        appendLog(id, now, actor, "提交处理方案",
                "赔付 ¥" + fmtYuan(saved.getCompensationAmountCents()) + "（" + saved.getSignTier() + "）");
        audit.record("COMPLAINT", saved.getComplaintNo(), actor, "SUBMIT",
                "{\"complaintNo\":\"" + esc(saved.getComplaintNo()) + "\",\"compensationCents\":"
                        + saved.getCompensationAmountCents() + ",\"signTier\":\"" + saved.getSignTier() + "\"}");
        return toView(saved, timelineOf(id));
    }

    /** 结案审批通过：待审批→已结案；记结案人/时刻；落 APPROVE_CLOSE 审计。 */
    @Transactional
    public ComplaintView approveClose(Long id) {
        Complaint c = mustGet(id);
        mustTransit(c, Complaint.STATUS_CLOSED);
        String actor = DataScope.currentActor();
        OffsetDateTime now = OffsetDateTime.now();
        c.setStatus(Complaint.STATUS_CLOSED);
        c.setClosedByName(actor);
        c.setClosedAt(now);
        Complaint saved = complaintRepository.save(c);
        appendLog(id, now, actor, "审批结案", null);
        audit.record("COMPLAINT", saved.getComplaintNo(), actor, "APPROVE_CLOSE",
                "{\"complaintNo\":\"" + esc(saved.getComplaintNo()) + "\"}");
        return toView(saved, timelineOf(id));
    }

    /** 退回补充处理：待审批→处理中；退回原因必填；落 SEND_BACK 审计。 */
    @Transactional
    public ComplaintView sendBack(Long id, String note) {
        Complaint c = mustGet(id);
        mustTransit(c, Complaint.STATUS_PROCESSING);
        if (note == null || note.isBlank()) {
            throw new BadReq("退回原因必填");
        }
        String actor = DataScope.currentActor();
        OffsetDateTime now = OffsetDateTime.now();
        c.setStatus(Complaint.STATUS_PROCESSING);
        Complaint saved = complaintRepository.save(c);
        appendLog(id, now, actor, "退回补充处理", note.trim());
        audit.record("COMPLAINT", saved.getComplaintNo(), actor, "SEND_BACK",
                "{\"complaintNo\":\"" + esc(saved.getComplaintNo()) + "\",\"note\":\""
                        + esc(note.trim()) + "\"}");
        return toView(saved, timelineOf(id));
    }

    /** 驳回（受理/审批环节判为无效投诉）：可迁态→已驳回；原因必填存 rejection_reason；落 REJECT 审计。 */
    @Transactional
    public ComplaintView reject(Long id, String reason) {
        Complaint c = mustGet(id);
        mustTransit(c, Complaint.STATUS_REJECTED);
        if (reason == null || reason.isBlank()) {
            throw new BadReq("驳回原因必填");
        }
        String actor = DataScope.currentActor();
        OffsetDateTime now = OffsetDateTime.now();
        c.setStatus(Complaint.STATUS_REJECTED);
        c.setRejectionReason(reason.trim());
        Complaint saved = complaintRepository.save(c);
        appendLog(id, now, actor, "驳回投诉", reason.trim());
        audit.record("COMPLAINT", saved.getComplaintNo(), actor, "REJECT",
                "{\"complaintNo\":\"" + esc(saved.getComplaintNo()) + "\",\"reason\":\""
                        + esc(reason.trim()) + "\"}");
        return toView(saved, timelineOf(id));
    }

    /** 签署层级服务端统算（与前端 dualSign 阈值同源；l1:1000 为展示下限不参与判定）。 */
    static String tierFor(long cents) {
        long yuan = cents / 100;
        if (yuan >= TIER_L3_MIN_YUAN) return Complaint.TIER_L3;
        if (yuan >= TIER_L2_MIN_YUAN) return Complaint.TIER_L2;
        return Complaint.TIER_L1;
    }

    private Complaint mustGet(Long id) {
        return complaintRepository.findById(id).orElseThrow(() -> new NotFound("客诉单不存在"));
    }

    /** 状态机前置校验：违反→409 中文透出当前态（幂等提交方据此识别已流转）。 */
    private static void mustTransit(Complaint c, String to) {
        if (!TRANSITIONS.getOrDefault(c.getStatus(), Set.of()).contains(to)) {
            throw new Conflict("当前状态「" + statusLabel(c.getStatus()) + "」不允许此操作");
        }
    }

    /** 当日单号：TS+yyyyMMdd-3 位序号，取 DB 当日最大号+1（与 RiskService.nextRiskNo 同构）。 */
    private synchronized String nextComplaintNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "TS" + day + "-";
        int seq = 1;
        var max = complaintRepository.findTopByComplaintNoLikeOrderByComplaintNoDesc(prefix + "%");
        if (max.isPresent()) {
            String tail = max.get().getComplaintNo().substring(prefix.length());
            seq = Integer.parseInt(tail) + 1;
        }
        return prefix + String.format("%03d", seq);
    }

    private void appendLog(Long complaintId, OffsetDateTime at, String by, String action, String note) {
        ComplaintLog l = new ComplaintLog();
        l.setComplaintId(complaintId);
        l.setAtTime(at);
        l.setByName(by);
        l.setAction(action);
        l.setNote(note);
        logRepository.save(l);
    }

    private List<TimelineItem> timelineOf(Long complaintId) {
        return logRepository.findByComplaintIdOrderByAtTimeAscIdAsc(complaintId).stream()
                .map(ComplaintService::toTimelineItem)
                .toList();
    }

    private static TimelineItem toTimelineItem(ComplaintLog l) {
        return new TimelineItem(l.getAtTime() == null ? "" : l.getAtTime().toString(),
                l.getByName(), l.getAction(), l.getNote());
    }

    /** complaintAutoTask=1（m3_settings，缺省 true）时下发跟进任务；软降级不阻断客诉操作。 */
    private void dispatchFollowTask(Complaint c, String phase) {
        if (!autoTaskEnabled()) {
            return;
        }
        String priority = Complaint.SEVERITY_HIGH.equals(c.getSeverity()) ? "HIGH" : "MEDIUM";
        followTaskClient.createFollowTask("COMPLAINT", c.getComplaintNo(),
                c.getCustomerId() == null ? "" : c.getCustomerId(), c.getCustomerName(), "",
                "PHONE",
                "客诉跟进：" + c.getComplaintNo() + "（" + c.getCustomerName() + "·"
                        + severityLabel(c.getSeverity()) + "）",
                priority, c.getStoreCode(), "complaint-" + phase + "-" + c.getId());
    }

    private boolean autoTaskEnabled() {
        try {
            Object v = m3SettingsService.loadMerged().get("complaintAutoTask");
            return v instanceof Boolean b ? b : true;
        } catch (Exception e) {
            return true;
        }
    }

    private ComplaintView toView(Complaint c, List<TimelineItem> timeline) {
        return new ComplaintView(
                c.getId(),
                c.getComplaintNo(),
                c.getCustomerId() == null ? "" : c.getCustomerId(),
                c.getCustomerName(),
                c.getSource(),
                c.getSeverity(),
                c.getCategory(),
                Boolean.TRUE.equals(c.getMedicalRisk()),
                c.getDescription(),
                c.getRelatedOrderNo(),
                c.getStoreCode() == null ? "" : c.getStoreCode(),
                c.getStoreName() == null ? "" : c.getStoreName(),
                c.getStatus(),
                c.getCompensationAmountCents() == null ? 0.0 : c.getCompensationAmountCents() / 100.0,
                c.getSignTier(),
                c.getResolution(),
                c.getCreatedAt() == null ? "" : c.getCreatedAt().toString(),
                c.getAcceptedByName(),
                c.getAcceptedAt() == null ? null : c.getAcceptedAt().toString(),
                c.getSubmittedByName(),
                c.getSubmittedAt() == null ? null : c.getSubmittedAt().toString(),
                c.getClosedByName(),
                c.getClosedAt() == null ? null : c.getClosedAt().toString(),
                c.getRejectionReason(),
                timeline);
    }

    /** 赔付金额元展示：整元不带小数（与前端 mock「赔付 ¥3000（L1）」文案一致）。 */
    private static String fmtYuan(long cents) {
        return cents % 100 == 0 ? String.valueOf(cents / 100) : String.format("%.2f", cents / 100.0);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 状态中文标签（4xx 报错透出当前态，便于前端/运维直读）。 */
    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case Complaint.STATUS_PENDING_ACCEPT -> "待受理";
            case Complaint.STATUS_PROCESSING -> "处理中";
            case Complaint.STATUS_PENDING_REVIEW -> "待结案审批";
            case Complaint.STATUS_CLOSED -> "已结案";
            case Complaint.STATUS_REJECTED -> "已驳回";
            default -> status == null ? "未知" : status;
        };
    }

    private static String severityLabel(String severity) {
        return switch (severity == null ? "" : severity) {
            case Complaint.SEVERITY_HIGH -> "高";
            case Complaint.SEVERITY_MEDIUM -> "中";
            case Complaint.SEVERITY_LOW -> "低";
            default -> severity == null ? "未知" : severity;
        };
    }
}
