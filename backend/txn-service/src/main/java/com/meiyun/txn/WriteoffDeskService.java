package com.meiyun.txn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M2 划扣核销台领域服务：任务生成（签到自动 / 老客手工建单）、双签执行、异常标记/解除、队列查询。
 *
 * <p>B6 G1：双签执行的扣次/扣额不再本地直写 member_card，改为经 {@link CustomerCardClient} 调
 * customer 权威卡台账（行锁 + WO 单号幂等 + CONSUME 流水）；customer 失败即抛异常回滚本事务，
 * 杜绝「划扣成立卡未扣」。本服务保留卡归属/状态预检（快速中文失败）与账实金额测算。
 *
 * <p>铁律：操作人一律取 {@link DataScope#currentActor()}（请求体 operator 不可信忽略）；
 * 金额单位「分」，单次划扣额 = floor(卡余额 / 剩余次数)；写动作全程审计（bizType=WDESK，JSON payload）；
 * 非法入参中文 4xx，越权统一 404 不泄露存在性。
 */
@Service
public class WriteoffDeskService {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_DONE = "DONE";
    public static final String ST_EXCEPTION = "EXCEPTION";

    public static final String SRC_APPOINTMENT = "APPOINTMENT";
    public static final String SRC_WALKIN = "WALKIN";

    private static final Map<String, String> EXCEPTION_TEXT = Map.of(
            "CUSTOMER_ABSENT", "客户未到",
            "COUNT_MISMATCH", "次数不符",
            "EQUIPMENT_FAULT", "设备故障",
            "OTHER", "其他");

    private final WriteoffDeskTaskRepository wdRepo;
    private final MemberCardRepository cardRepo;
    private final WriteoffRepository writeoffRepo;
    private final WriteoffNoGenerator noGen;
    private final AuditRecorder audit;
    private final ApptRefNameResolver names;
    private final FinanceEventPublisher financeEvents;
    private final CustomerCardClient customerCardClient;
    private final BomDeductService bomDeductService;
    private final OrgStaffClient orgStaffClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WriteoffDeskService(WriteoffDeskTaskRepository wdRepo, MemberCardRepository cardRepo,
                               WriteoffRepository writeoffRepo, WriteoffNoGenerator noGen,
                               AuditRecorder audit, ApptRefNameResolver names,
                               FinanceEventPublisher financeEvents, CustomerCardClient customerCardClient,
                               BomDeductService bomDeductService, OrgStaffClient orgStaffClient) {
        this.wdRepo = wdRepo;
        this.cardRepo = cardRepo;
        this.writeoffRepo = writeoffRepo;
        this.noGen = noGen;
        this.audit = audit;
        this.names = names;
        this.financeEvents = financeEvents;
        this.customerCardClient = customerCardClient;
        this.bomDeductService = bomDeductService;
        this.orgStaffClient = orgStaffClient;
    }

    // ==================== 任务生成 ====================

    /**
     * 预约签到自动生成待划扣任务（同预约号幂等：已有任务直接返回既有任务）。
     * 绑客户本店在用最新卡（createdAt desc 第一张）；无在用卡时仍建任务（cardNo 留空，双签时手工选卡/提示）。
     */
    @Transactional
    public WriteoffDeskTask createFromAppointment(Appointment a) {
        List<WriteoffDeskTask> exist = wdRepo.findByApptNo(a.getApptNo());
        if (!exist.isEmpty()) {
            return exist.get(0);
        }
        MemberCard card = latestActiveCard(a.getCustomerId(), a.getStoreCode());
        OffsetDateTime apptTime = OffsetDateTime.of(
                LocalDateTime.of(a.getApptDate(), LocalTime.parse(a.getApptTime())),
                ZoneOffset.ofHours(8));
        WriteoffDeskTask t = newTask(a.getCustomerId(), a.getStoreCode(), a.getProject(), card,
                SRC_APPOINTMENT, a.getApptNo(), apptTime, DataScope.currentActor());
        appendTimeline(t, "系统", "预约签到自动生成待划扣任务（预约号 " + a.getApptNo() + "）");
        WriteoffDeskTask saved = wdRepo.save(t);
        audit.record("WDESK", saved.getWdNo(), DataScope.currentActor(), "AUTO_CREATE",
                "{\"source\":\"APPOINTMENT\",\"apptNo\":\"" + a.getApptNo()
                        + "\",\"customer\":\"" + a.getCustomerId() + "\",\"project\":\"" + esc(a.getProject()) + "\"}");
        return saved;
    }

    /**
     * 老客未预约到店手工建单（WALKIN）。校验：客户/门店存在（服务间）、项目非空、本店有在用卡。
     * 新客直接到店不在此列——须先走前台登记分诊预约流程。
     */
    @Transactional
    public WriteoffDeskTask createWalkin(String customerId, String storeCode, String project, String cardNo) {
        String actor = DataScope.currentActor();
        if (customerId == null || customerId.isBlank()) {
            throw badRequest("客户不能为空");
        }
        if (storeCode == null || storeCode.isBlank()) {
            throw badRequest("门店不能为空");
        }
        if (project == null || project.isBlank()) {
            throw badRequest("核销项目不能为空");
        }
        if (!names.customerNames(List.of(customerId)).containsKey(customerId)) {
            throw badRequest("客户不存在: " + customerId + "（新客请先在前台登记建档后再建单）");
        }
        if (!names.storeNames(List.of(storeCode)).containsKey(storeCode)) {
            throw badRequest("门店不存在: " + storeCode);
        }
        MemberCard card;
        if (cardNo != null && !cardNo.isBlank()) {
            card = cardRepo.findById(cardNo)
                    .orElseThrow(() -> notFound("卡不存在: " + cardNo));
            if (!"在用".equals(card.getStatus())) {
                throw badRequest("卡状态非「在用」: " + card.getStatus());
            }
            if (!customerId.equals(card.getCustomerId()) || !storeCode.equals(card.getStoreCode())) {
                throw badRequest("所选卡不属于该客户或本门店");
            }
        } else {
            card = latestActiveCard(customerId, storeCode);
            if (card == null) {
                throw badRequest("该客户在本门店无在用会员卡，无法建划扣任务");
            }
        }
        WriteoffDeskTask t = newTask(customerId, storeCode, project, card, SRC_WALKIN, null,
                OffsetDateTime.now(), actor);
        appendTimeline(t, actor, "手工建单：老客未预约直接到店，创建待划扣任务");
        WriteoffDeskTask saved = wdRepo.save(t);
        audit.record("WDESK", saved.getWdNo(), actor, "CREATE",
                "{\"source\":\"WALKIN\",\"customer\":\"" + customerId + "\",\"store\":\"" + storeCode
                        + "\",\"project\":\"" + esc(project) + "\",\"card\":\"" + card.getCardNo()
                        + "\",\"amount\":" + saved.getAmount() + "}");
        return saved;
    }

    // ==================== 双签执行 ====================

    /**
     * 双签划扣执行：PENDING → DONE。同事务完成卡校验、扣次/扣额、写 writeoff_record（card_no 非空、
     * sign1=操作人工号、sign2=复核人「工号 姓名」）、任务回写。幂等：DONE 任务重复执行直接返回当前态。
     * 卡选择器：cardNo 非空时改卡扣减并回写任务；为空时用任务绑定卡（绑定卡也空则 400 提示选卡）。
     *
     * <p>B18 双签金额分级：reviewerId 为复核人真实工号，经 org 服务硬校验（存在/在职/角色，不可降级，
     * org 不可用 → 502 未执行划扣）；禁同人（复核人 ≠ 登录操作人）；按单次划扣额 tier：
     * L1（&lt;¥1,000）持 writeoff:create 角色员工互签（医生/操作师/前台/店长），L2（¥1,000~5,000）
     * 与 L3（≥¥20,000）复核人须店长（L3 前端另强提示区域经理）。
     */
    @Transactional
    public WriteoffDeskTask execute(String wdNo, String reviewerId, String cardNo, String remark) {
        WriteoffDeskTask t = requireTask(wdNo);
        if (ST_DONE.equals(t.getStatus())) {
            return t; // 幂等：已划扣，返回当前态
        }
        if (ST_EXCEPTION.equals(t.getStatus())) {
            throw badRequest("该任务处于异常状态，请先解除异常再划扣");
        }
        if (reviewerId == null || reviewerId.trim().isBlank()) {
            throw badRequest("双签复核人工号不能为空");
        }
        String actor = DataScope.currentActor();
        String rid = reviewerId.trim();
        if (rid.equals(actor)) {
            throw badRequest("复核人不能与操作人为同一人（须第二位员工双签复核）");
        }

        String useCardNo = (cardNo != null && !cardNo.isBlank()) ? cardNo.trim() : t.getCardNo();
        if (useCardNo == null || useCardNo.isBlank()) {
            throw badRequest("请选择划扣所用会员卡");
        }
        MemberCard card = cardRepo.findById(useCardNo)
                .orElseThrow(() -> notFound("卡不存在: " + useCardNo));
        if (!"在用".equals(card.getStatus())) {
            throw badRequest("卡状态非「在用」: " + card.getStatus());
        }
        if (!t.getCustomerId().equals(card.getCustomerId())) {
            throw badRequest("所选卡不属于该客户");
        }
        if (!t.getStoreCode().equals(card.getStoreCode())) {
            throw badRequest("所选卡不属于本门店");
        }
        if (card.getRemainTimes() == null || card.getRemainTimes() < 1) {
            throw badRequest("账实校验失败：卡剩余次数为 0，无法划扣");
        }
        long unit = card.getBalance() != null && card.getBalance() > 0
                ? card.getBalance() / card.getRemainTimes() : 0L;
        if (card.getBalance() != null && card.getBalance() < unit) {
            throw badRequest("账实校验失败：卡余额 " + card.getBalance() + " 分 < 单次划扣额 " + unit + " 分");
        }

        // B18 双签分级硬闸门：org 校验复核人工号存在 + 在职（不降级，失败 400/502，未动卡）；
        // 按单次划扣额 tier 校验角色——L1 四角色互签，L2/L3 须店长。
        String tier = TxnService.tierFor(unit);
        OrgStaffClient.StaffProfile reviewer = orgStaffClient.fetchStaff(rid);
        OrgStaffClient.requireReviewerRole(reviewer, tier);
        String display = rid + " " + reviewer.staffName();

        // B6 G1：WO 单号先于联动生成并作为幂等键（customer 成功后重试不双扣；失败整体回滚不留痕）
        String writeoffId = noGen.nextWriteoffNo();

        // 权威扣卡：customer 卡台账行锁扣次/扣额 + CONSUME 流水（4xx 中文透传 / 5xx 网络异常 502，失败即中止回滚）
        customerCardClient.writeoff(card.getCardNo(), writeoffId, 1, unit, t.getStoreCode(), false);

        // 落划扣记录（卡扣次：card_no 非空，status=DONE，sign1/sign2 双签留痕，sign2 为「工号 姓名」）
        WriteoffRecord w = new WriteoffRecord();
        w.setWriteoffId(writeoffId);
        w.setCardNo(card.getCardNo());
        w.setCustomerId(card.getCustomerId());
        w.setStoreCode(t.getStoreCode());
        w.setProject(t.getProject());
        w.setTimesUsed(1);
        w.setAmount(unit);
        w.setOperator(actor);
        w.setStatus("DONE");
        w.setSign1(actor);
        w.setSign2(display);
        writeoffRepo.save(w);

        // B3 合规写：卡扣划扣完成同事务入资金事件 outbox（预收转出+确认收入成对；unit=0 纯扣次跳过）
        financeEvents.emitWriteoffDone(w);

        t.setStatus(ST_DONE);
        t.setReviewer(display);
        t.setReviewerId(rid);
        t.setCardNo(card.getCardNo());
        t.setAmount(unit);
        t.setWriteoffId(w.getWriteoffId());
        t.setExecutedAt(OffsetDateTime.now());
        if (remark != null && !remark.isBlank()) {
            t.setNote(remark.trim());
        }
        appendTimeline(t, actor, "双签划扣完成（" + tier + " 级），复核人：" + display
                + "；扣卡 " + card.getCardNo() + " 1 次/" + unit + " 分，划扣单号 " + w.getWriteoffId());
        WriteoffDeskTask saved = wdRepo.save(t);

        audit.record("WDESK", wdNo, actor, "EXECUTE",
                "{\"card\":\"" + card.getCardNo() + "\",\"writeoffId\":\"" + w.getWriteoffId()
                        + "\",\"reviewerId\":\"" + esc(rid) + "\",\"reviewer\":\"" + esc(display)
                        + "\",\"reviewerRole\":\"" + esc(reviewer.primaryRole())
                        + "\",\"tier\":\"" + tier + "\",\"amount\":" + unit
                        + ",\"timesUsed\":1,\"authority\":\"customer\"}");

        // B10：BOM 自动扣料注册在划扣事务提交后执行（扣库/成本事件/异常登记均不在本事务内，
        // 失败只登记 bom_deduct_exception，绝不阻断或回滚划扣——医疗红线）
        bomDeductService.triggerAfterCommit(w.getWriteoffId(), w.getStoreCode(), w.getProject(), actor);
        return saved;
    }

    // ==================== 异常标记 / 解除 ====================

    /** 标记异常：PENDING → EXCEPTION（DONE 不可标）。 */
    @Transactional
    public WriteoffDeskTask markException(String wdNo, String reason, String note) {
        WriteoffDeskTask t = requireTask(wdNo);
        if (ST_DONE.equals(t.getStatus())) {
            throw badRequest("已划扣任务不可标记异常");
        }
        if (ST_EXCEPTION.equals(t.getStatus())) {
            throw badRequest("该任务已是异常状态");
        }
        if (reason == null || !EXCEPTION_TEXT.containsKey(reason)) {
            throw badRequest("异常原因非法（支持：客户未到/次数不符/设备故障/其他）");
        }
        String actor = DataScope.currentActor();
        t.setStatus(ST_EXCEPTION);
        t.setExceptionReason(reason);
        if (note != null && !note.isBlank()) {
            t.setNote(note.trim());
        }
        String text = "标记异常：" + EXCEPTION_TEXT.get(reason)
                + (note != null && !note.isBlank() ? "（" + note.trim() + "）" : "");
        appendTimeline(t, actor, text);
        WriteoffDeskTask saved = wdRepo.save(t);
        audit.record("WDESK", wdNo, actor, "EXCEPTION",
                "{\"reason\":\"" + reason + "\",\"note\":\"" + esc(note == null ? "" : note.trim()) + "\"}");
        return saved;
    }

    /** 解除异常：EXCEPTION → PENDING。 */
    @Transactional
    public WriteoffDeskTask reset(String wdNo) {
        WriteoffDeskTask t = requireTask(wdNo);
        if (!ST_EXCEPTION.equals(t.getStatus())) {
            throw badRequest("仅异常状态任务可解除异常，当前: " + t.getStatus());
        }
        String actor = DataScope.currentActor();
        t.setStatus(ST_PENDING);
        t.setExceptionReason("NONE");
        appendTimeline(t, actor, "异常已解除，重新进入待执行队列");
        WriteoffDeskTask saved = wdRepo.save(t);
        audit.record("WDESK", wdNo, actor, "RESET", "{}");
        return saved;
    }

    // ==================== 查询 ====================

    /** 队列查询：数据域强制注入 + 日期/门店/状态过滤，按到店时间倒序。 */
    public List<WriteoffDeskTask> list(LocalDate date, String storeCode, String status) {
        if (storeCode != null && !storeCode.isBlank() && !DataScope.canReadStore(storeCode)) {
            throw notFound("数据不存在或无权查看");
        }
        Specification<WriteoffDeskTask> spec = DataScope.storeSpec("storeCode");
        if (date != null) {
            OffsetDateTime start = date.atStartOfDay().atOffset(ZoneOffset.ofHours(8));
            OffsetDateTime end = date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHours(8));
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("appointmentTime"), start));
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("appointmentTime"), end));
        }
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return wdRepo.findAll(spec, Sort.by(Sort.Order.desc("appointmentTime")));
    }

    /** 客户本店在用卡列表（双签弹窗卡选择器 / 手工建单选卡）：按开卡时间倒序。 */
    public List<MemberCard> activeCards(String customerId, String storeCode) {
        if (customerId == null || customerId.isBlank()) {
            throw badRequest("客户不能为空");
        }
        Specification<MemberCard> spec = (root, q, cb) -> cb.equal(root.get("customerId"), customerId);
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), "在用"));
        spec = spec.and(DataScope.storeSpec("storeCode"));
        return cardRepo.findAll(spec, Sort.by(Sort.Order.desc("createdAt")));
    }

    // ==================== 内部 ====================

    private WriteoffDeskTask newTask(String customerId, String storeCode, String project, MemberCard card,
                                     String source, String apptNo, OffsetDateTime appointmentTime, String actor) {
        WriteoffDeskTask t = new WriteoffDeskTask();
        t.setWdNo(noGen.nextWdNo());
        t.setStatus(ST_PENDING);
        t.setExceptionReason("NONE");
        t.setSource(source);
        t.setCustomerId(customerId);
        t.setStoreCode(storeCode);
        t.setProject(project);
        t.setApptNo(apptNo);
        t.setAppointmentTime(appointmentTime);
        t.setOperator(actor);
        if (card != null) {
            t.setCardNo(card.getCardNo());
            t.setAmount(card.getBalance() != null && card.getRemainTimes() != null && card.getRemainTimes() > 0
                    ? card.getBalance() / card.getRemainTimes() : 0L);
        } else {
            t.setAmount(0L);
        }
        t.setTimeline("[]");
        return t;
    }

    /** 客户本店在用最新卡（createdAt desc 第一张）；无则 null。 */
    private MemberCard latestActiveCard(String customerId, String storeCode) {
        List<MemberCard> cards = activeCards(customerId, storeCode);
        return cards.isEmpty() ? null : cards.get(0);
    }

    private WriteoffDeskTask requireTask(String wdNo) {
        WriteoffDeskTask t = wdRepo.findById(wdNo)
                .orElseThrow(() -> notFound("数据不存在或无权查看"));
        if (!DataScope.canReadStore(t.getStoreCode())) {
            throw notFound("数据不存在或无权查看");
        }
        return t;
    }

    /** timeline 追加一条（by/text/at），JSON 数组落库。 */
    private void appendTimeline(WriteoffDeskTask t, String by, String text) {
        List<Map<String, String>> lines = readTimeline(t.getTimeline());
        Map<String, String> line = new LinkedHashMap<>();
        line.put("by", by);
        line.put("text", text);
        line.put("at", OffsetDateTime.now().toString());
        lines.add(line);
        try {
            t.setTimeline(objectMapper.writeValueAsString(lines));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "时间线序列化失败");
        }
    }

    /** 读取 timeline JSON（空/脏数据降级为空列表）。 */
    public List<Map<String, String>> readTimeline(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
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
