package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接待台 / 候诊分诊领域服务：到店登记（手工/预约签到自动）、分诊入位（同事务建方案空草稿）、
 * 改派、叫号、完成、队列查询。
 *
 * <p>铁律：操作人/门店一律取 {@link DataScope} 当前登录上下文（请求体 operator/storeCode 不可信）；
 * 外键（客户/门店/被分派人）经服务间调用硬校验；状态机非法流转中文 400，越权统一 404；
 * 全动作审计（bizType=ARRIVAL，手工拼合法 JSON payload）。
 *
 * <p>分诊联动：内部直调 {@link ConsultPlanService#saveDraft} 建 PENDING 空草稿（不经 HTTP，
 * 不受前台无 consult:edit 权限限制），CONSULT/SERVICE 草稿挂咨询师表、MEDICAL 挂医生表，
 * triage.plan_id 回挂草稿号；改派不动草稿归属（对齐 mock 活规格）。
 */
@Service
public class ArrivalService {

    public static final String ST_WAITING = "WAITING";
    public static final String ST_TRIAGED = "TRIAGED";
    public static final String ST_CALLED = "CALLED";
    public static final String ST_DONE = "DONE";
    public static final String ST_LEFT = "LEFT";

    public static final String CH_WALK_IN = "WALK_IN";
    public static final String CH_REFERRAL = "REFERRAL";
    public static final String CH_MARKETING = "MARKETING";
    public static final String CH_APPOINTMENT = "APPOINTMENT";

    private static final Set<String> CHANNELS = Set.of(CH_WALK_IN, CH_REFERRAL, CH_MARKETING, CH_APPOINTMENT);
    private static final Set<String> TRIAGE_TYPES = Set.of("CONSULT", "MEDICAL", "SERVICE");

    private final ArrivalRepository arrivalRepo;
    private final TriageRepository triageRepo;
    private final TriageReassignRepository reassignRepo;
    private final ArrivalNoGenerator noGen;
    private final AuditRecorder audit;
    private final ApptRefNameResolver names;
    private final OrgStaffClient orgStaffClient;
    private final ConsultPlanService consultPlanService;
    private final WaitlistService waitlistService;

    public ArrivalService(ArrivalRepository arrivalRepo, TriageRepository triageRepo,
                          TriageReassignRepository reassignRepo,
                          ArrivalNoGenerator noGen, AuditRecorder audit, ApptRefNameResolver names,
                          OrgStaffClient orgStaffClient, ConsultPlanService consultPlanService,
                          @Lazy WaitlistService waitlistService) {
        this.arrivalRepo = arrivalRepo;
        this.triageRepo = triageRepo;
        this.reassignRepo = reassignRepo;
        this.noGen = noGen;
        this.audit = audit;
        this.names = names;
        this.orgStaffClient = orgStaffClient;
        this.consultPlanService = consultPlanService;
        this.waitlistService = waitlistService;
    }

    // ==================== 到店登记 ====================

    /**
     * 前台手工到店登记（自然到店/转介绍/线上渠道）。门店取 JWT 当前本店（不信入参），
     * 客户经服务间校验存在，queue_no 取门店当日 max+1。
     */
    @Transactional
    public Arrival create(String customerId, String channel, String note) {
        String actor = DataScope.currentActor();
        LoginUser user = DataScope.current();
        String storeCode = user == null ? null : user.storeCode();
        if (customerId == null || customerId.isBlank()) {
            throw badRequest("客户不能为空（新客请先在客情建档后再登记到店）");
        }
        if (storeCode == null || storeCode.isBlank()) {
            throw badRequest("当前登录人未归属门店，无法登记到店");
        }
        if (!names.customerNames(List.of(customerId)).containsKey(customerId)) {
            throw badRequest("客户不存在: " + customerId + "（新客请先在客情建档后再登记到店）");
        }
        if (!names.storeNames(List.of(storeCode)).containsKey(storeCode)) {
            throw badRequest("门店不存在或已停用: " + storeCode);
        }
        String ch = channel == null || channel.isBlank() ? CH_WALK_IN : channel.trim();
        if (!CHANNELS.contains(ch)) {
            throw badRequest("非法到店渠道: " + ch);
        }
        Arrival a = newArrival(customerId.trim(), storeCode, ch, note, null, actor);
        Arrival saved = arrivalRepo.save(a);
        audit.record("ARRIVAL", saved.getAhNo(), actor, "CHECK_IN",
                "{\"customer\":\"" + saved.getCustomerId() + "\",\"store\":\"" + saved.getStoreCode()
                        + "\",\"channel\":\"" + ch + "\",\"queueNo\":" + saved.getQueueNo() + "}");
        return saved;
    }

    /**
     * 预约签到自动生成到店登记（channel=APPOINTMENT，apptNo 幂等）。与 M2 核销任务同处签到事务，
     * 同生共死；仅建档客户预约生成（预约无 customerId 时跳过）。
     */
    @Transactional
    public Arrival createFromAppointment(Appointment appt) {
        if (appt == null || appt.getCustomerId() == null || appt.getCustomerId().isBlank()) {
            return null;
        }
        List<Arrival> exist = arrivalRepo.findByApptNo(appt.getApptNo());
        if (!exist.isEmpty()) {
            return exist.get(0);
        }
        String actor = DataScope.currentActor();
        Arrival a = newArrival(appt.getCustomerId(), appt.getStoreCode(), CH_APPOINTMENT,
                null, appt.getApptNo(), actor);
        Arrival saved = arrivalRepo.save(a);
        audit.record("ARRIVAL", saved.getAhNo(), actor, "AUTO_CREATE",
                "{\"source\":\"APPOINTMENT\",\"apptNo\":\"" + appt.getApptNo()
                        + "\",\"customer\":\"" + saved.getCustomerId() + "\",\"queueNo\":"
                        + saved.getQueueNo() + "}");
        return saved;
    }

    // ==================== 分诊 / 改派 ====================

    /**
     * 分诊入位：WAITING → TRIAGED（CALLED 回退后允许重新分诊；已 TRIAGED 请走改派）。
     * 被分派人经 org 硬校验在职；MEDICAL 须 DOCTOR 资质。同事务 upsert 分诊单并创建
     * consult_plan 空 PENDING 草稿（仅首次分诊建草稿；重新分诊复用既有草稿号）。
     */
    @Transactional
    public Arrival triage(String ahNo, String type, String assignedTo, String note) {
        Arrival a = requireArrival(ahNo);
        String actor = DataScope.currentActor();
        if (ST_TRIAGED.equals(a.getStatus())) {
            throw badRequest("该客户已分诊，改派请使用「改派」操作");
        }
        if (!ST_WAITING.equals(a.getStatus()) && !ST_CALLED.equals(a.getStatus())) {
            throw badRequest("仅候诊中（或叫号退回）的登记可分诊，当前状态: " + a.getStatus());
        }
        String tp = type == null ? "" : type.trim();
        String who = assignedTo == null ? "" : assignedTo.trim();
        if (!TRIAGE_TYPES.contains(tp)) {
            throw badRequest("非法分诊类型: " + type + "（仅支持 CONSULT/MEDICAL/SERVICE）");
        }
        if (who.isBlank()) {
            throw badRequest("请选择分诊负责人");
        }
        OrgStaffClient.StaffProfile staff = fetchEmployed(who);
        if ("MEDICAL".equals(tp) && !hasDoctorRole(staff)) {
            throw badRequest("医疗类分诊的负责人须具备医生（DOCTOR）资质："
                    + staff.staffId() + " 当前角色不满足");
        }

        Triage t = triageRepo.findByArrivalId(ahNo).orElse(null);
        String planId;
        if (t == null) {
            t = new Triage();
            t.setTrNo(noGen.nextTrNo());
            t.setArrivalId(ahNo);
            t.setCustomerId(a.getCustomerId());
            t.setStoreCode(a.getStoreCode());
            t.setCreatedBy(actor);
            t.setAssignedTo(who);
            String consultantId = "MEDICAL".equals(tp) ? null : who;
            String doctorId = "MEDICAL".equals(tp) ? who : null;
            ConsultPlanService.PlanView draft = consultPlanService.saveDraft(
                    new ConsultPlanService.SaveDraftCmd(null, ahNo, a.getCustomerId(), a.getStoreCode(),
                            consultantId, doctorId, null, null, null,
                            null, null, null, null, null, null, actor));
            planId = draft.planId();
            t.setPlanId(planId);
        } else {
            // CALLED 回退后重新分诊：复用既有草稿，更新首诊负责人与编辑留痕
            planId = t.getPlanId();
            t.setAssignedTo(who);
            t.setForwardedTo(null);
            t.setEditedBy(actor);
            t.setEditedAt(OffsetDateTime.now());
        }
        t.setType(tp);
        if (note != null && !note.isBlank()) {
            t.setNote(note.trim());
        }
        triageRepo.save(t);

        a.setStatus(ST_TRIAGED);
        arrivalRepo.save(a);

        audit.record("ARRIVAL", ahNo, actor, "TRIAGE",
                "{\"type\":\"" + tp + "\",\"assignedTo\":\"" + esc(who)
                        + "\",\"planId\":\"" + (planId == null ? "" : planId) + "\"}");
        return a;
    }

    /**
     * 改派：仅校验新负责人在职（MEDICAL 资质不重复卡——跨店策略由前端 config 门控，
     * 设置中心后端化见 Backlog）；更新 triage.forwarded_to 并同事务在 triage_reassign
     * 追加一行历史，不动方案草稿归属（对齐 mock）。
     */
    @Transactional
    public Arrival reassign(String ahNo, String newAssignedTo) {
        Arrival a = requireArrival(ahNo);
        Triage t = triageRepo.findByArrivalId(ahNo)
                .orElseThrow(() -> badRequest("该登记尚未分诊，无可改派的分诊单"));
        String who = newAssignedTo == null ? "" : newAssignedTo.trim();
        if (who.isBlank()) {
            throw badRequest("请选择改派目标负责人");
        }
        if (!ST_TRIAGED.equals(a.getStatus()) && !ST_CALLED.equals(a.getStatus())) {
            throw badRequest("仅已分诊/已叫号的登记可改派，当前状态: " + a.getStatus());
        }
        String actor = DataScope.currentActor();
        String from = currentOwner(t);
        if (who.equals(from)) {
            throw badRequest("改派目标与当前负责人相同，无需改派");
        }
        OrgStaffClient.StaffProfile staff = fetchEmployed(who);

        TriageReassign history = new TriageReassign();
        history.setTrNo(t.getTrNo());
        history.setArrivalId(ahNo);
        history.setStoreCode(a.getStoreCode());
        history.setFromStaff(from);
        history.setToStaff(staff.staffId());
        history.setOperator(actor);
        reassignRepo.save(history);

        t.setForwardedTo(staff.staffId());
        t.setEditedBy(actor);
        t.setEditedAt(OffsetDateTime.now());
        triageRepo.save(t);
        audit.record("ARRIVAL", ahNo, actor, "REASSIGN",
                "{\"from\":\"" + esc(from) + "\",\"to\":\"" + esc(staff.staffId()) + "\"}");
        return a;
    }

    /** 叫号：TRIAGED → CALLED（已 CALLED 幂等返回；WAITING 须先分诊）。 */
    @Transactional
    public Arrival call(String ahNo) {
        Arrival a = requireArrival(ahNo);
        if (ST_CALLED.equals(a.getStatus())) {
            return a;
        }
        if (!ST_TRIAGED.equals(a.getStatus())) {
            throw badRequest("仅已分诊的登记可叫号，当前状态: " + a.getStatus());
        }
        a.setStatus(ST_CALLED);
        a.setCalledAt(OffsetDateTime.now());
        arrivalRepo.save(a);
        audit.record("ARRIVAL", ahNo, DataScope.currentActor(), "CALL", "{}");
        return a;
    }

    /** 完成接诊：TRIAGED/CALLED → DONE（DONE 幂等返回）。 */
    @Transactional
    public Arrival done(String ahNo) {
        Arrival a = requireArrival(ahNo);
        if (ST_DONE.equals(a.getStatus())) {
            return a;
        }
        if (!ST_TRIAGED.equals(a.getStatus()) && !ST_CALLED.equals(a.getStatus())) {
            throw badRequest("仅已分诊/已叫号的登记可完成接诊，当前状态: " + a.getStatus());
        }
        a.setStatus(ST_DONE);
        a.setDoneAt(OffsetDateTime.now());
        arrivalRepo.save(a);
        audit.record("ARRIVAL", ahNo, DataScope.currentActor(), "DONE", "{}");
        return a;
    }

    // ==================== 号源释放（手工 / 超时自动） ====================

    /**
     * 前台手工释放号源：WAITING → LEFT（leftAt 落库），并在同事务触发本店候补递补（无候补则仅释放）。
     * 越权统一 404（requireArrival 已断言门店数据域）；非 WAITING 中文 400。
     */
    @Transactional
    public Arrival release(String ahNo) {
        Arrival a = requireArrival(ahNo);
        if (!ST_WAITING.equals(a.getStatus())) {
            throw badRequest("仅候诊中的登记可释放号源，当前状态: " + a.getStatus());
        }
        return releaseInternal(a, DataScope.currentActor(), "MANUAL", true);
    }

    /**
     * 超时自动释放（Job 无登录上下文调用）：按 ahNo 在本事务内重新加载，WAITING → LEFT（leftAt 落库）
     * + 同事务候补递补通知。不做数据域断言（系统线程 DataScope 为空本就全量可见，ahNo 由 Job 扫描得出）；
     * 候补通知无可用目标（org 不可用/全免打扰/无候补）不阻断释放——号源释放本身必须生效。
     * 记录已不存在/已非 WAITING（崩溃重入、手工抢先处理）返回 null，由 Job 计为跳过。
     */
    @Transactional
    public Arrival releaseTimeoutBySystem(String ahNo) {
        Arrival a = arrivalRepo.findById(ahNo).orElse(null);
        if (a == null || !ST_WAITING.equals(a.getStatus())) {
            return null;
        }
        return releaseInternal(a, "system", "TIMEOUT", true);
    }

    /**
     * 释放内核：幂等（非 WAITING 直接返回当前态，供 Job 崩溃重入）；promoteWaitlist=false 时不递补
     * （保留给纯释放场景）。调用方须经本类 Spring 代理的事务方法进入（手工 release / Job releaseTimeoutBySystem）。
     */
    private Arrival releaseInternal(Arrival a, String actor, String reason, boolean promoteWaitlist) {
        if (!ST_WAITING.equals(a.getStatus())) {
            return a;
        }
        OffsetDateTime now = OffsetDateTime.now();
        a.setStatus(ST_LEFT);
        a.setLeftAt(now);
        arrivalRepo.save(a);
        boolean promoted = false;
        if (promoteWaitlist) {
            promoted = waitlistService.promoteNext(a.getStoreCode());
        }
        audit.record("ARRIVAL", a.getAhNo(), actor, "RELEASE",
                "{\"reason\":\"" + reason + "\",\"queueNo\":" + a.getQueueNo()
                        + ",\"waitlistPromoted\":" + promoted + "}");
        return a;
    }

    // ==================== 查询 ====================

    /** 队列查询：数据域强制门店注入 + 日期/门店/状态过滤，按到店时间倒序。 */
    public List<Arrival> list(LocalDate date, String storeCode, String status) {
        if (storeCode != null && !storeCode.isBlank() && !DataScope.canReadStore(storeCode)) {
            throw notFound("数据不存在或无权查看");
        }
        Specification<Arrival> spec = DataScope.storeSpec("storeCode");
        if (date != null) {
            OffsetDateTime start = date.atStartOfDay().atOffset(ZoneOffset.ofHours(8));
            OffsetDateTime end = date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHours(8));
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("arrivedAt"), start));
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("arrivedAt"), end));
        }
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return arrivalRepo.findAll(spec, Sort.by(
                Sort.Order.asc("queueNo"), Sort.Order.desc("arrivedAt")));
    }

    // ==================== 内部 ====================

    private Arrival newArrival(String customerId, String storeCode, String channel,
                               String note, String apptNo, String actor) {
        Arrival a = new Arrival();
        a.setAhNo(noGen.nextAhNo());
        a.setStatus(ST_WAITING);
        a.setCustomerId(customerId);
        a.setStoreCode(storeCode);
        a.setChannel(channel);
        LocalDate today = LocalDate.now(ZoneOffset.ofHours(8));
        OffsetDateTime start = today.atStartOfDay().atOffset(ZoneOffset.ofHours(8));
        OffsetDateTime end = today.plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHours(8));
        a.setQueueNo(arrivalRepo.maxQueueNo(storeCode, start, end) + 1);
        if (note != null && !note.isBlank()) {
            a.setNote(note.trim());
        }
        a.setApptNo(apptNo);
        a.setArrivedAt(OffsetDateTime.now());
        a.setOperator(actor);
        return a;
    }

    /** org 在职硬校验：fetchStaff 的双签语境文案不适用分诊，此处统一换成分诊语境中文。 */
    private OrgStaffClient.StaffProfile fetchEmployed(String staffId) {
        try {
            return orgStaffClient.fetchStaff(staffId);
        } catch (ResponseStatusException e) {
            HttpStatus st = HttpStatus.resolve(e.getStatusCode().value());
            String reason = e.getReason();
            if (st == HttpStatus.BAD_REQUEST) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "分诊负责人校验未通过：" + (reason == null ? staffId : reason)
                                .replace("复核人", "分诊负责人"));
            }
            throw e;
        }
    }

    private static boolean hasDoctorRole(OrgStaffClient.StaffProfile staff) {
        if ("DOCTOR".equals(staff.primaryRole())) {
            return true;
        }
        return staff.roles() != null && staff.roles().contains("DOCTOR");
    }

    private static String currentOwner(Triage t) {
        return t.getForwardedTo() != null && !t.getForwardedTo().isBlank()
                ? t.getForwardedTo() : t.getAssignedTo();
    }

    private Arrival requireArrival(String ahNo) {
        Arrival a = arrivalRepo.findById(ahNo)
                .orElseThrow(() -> notFound("数据不存在或无权查看"));
        if (!DataScope.canReadStore(a.getStoreCode())) {
            throw notFound("数据不存在或无权查看");
        }
        return a;
    }

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
