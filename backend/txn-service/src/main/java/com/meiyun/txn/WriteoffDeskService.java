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
 * M2 划扣核销台领域服务：任务生成（签到自动 / 老客手工建单）、双签执行（同事务卡扣次扣额）、
 * 异常标记/解除、队列查询。
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WriteoffDeskService(WriteoffDeskTaskRepository wdRepo, MemberCardRepository cardRepo,
                               WriteoffRepository writeoffRepo, WriteoffNoGenerator noGen,
                               AuditRecorder audit, ApptRefNameResolver names) {
        this.wdRepo = wdRepo;
        this.cardRepo = cardRepo;
        this.writeoffRepo = writeoffRepo;
        this.noGen = noGen;
        this.audit = audit;
        this.names = names;
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
     * sign1=操作人工号、sign2=复核人）、任务回写。幂等：DONE 任务重复执行直接返回当前态。
     * 卡选择器：cardNo 非空时改卡扣减并回写任务；为空时用任务绑定卡（绑定卡也空则 400 提示选卡）。
     */
    @Transactional
    public WriteoffDeskTask execute(String wdNo, String reviewer, String cardNo, String remark) {
        WriteoffDeskTask t = requireTask(wdNo);
        if (ST_DONE.equals(t.getStatus())) {
            return t; // 幂等：已划扣，返回当前态
        }
        if (ST_EXCEPTION.equals(t.getStatus())) {
            throw badRequest("该任务处于异常状态，请先解除异常再划扣");
        }
        if (reviewer == null || reviewer.trim().isBlank()) {
            throw badRequest("双签复核人不能为空");
        }
        String actor = DataScope.currentActor();

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

        // 同事务扣次/扣额
        card.setRemainTimes(card.getRemainTimes() - 1);
        if (unit > 0) {
            card.setBalance(card.getBalance() - unit);
        }
        if (card.getRemainTimes() == 0) {
            card.setStatus("已用完");
        }
        cardRepo.save(card);

        // 落划扣记录（卡扣次：card_no 非空，status=DONE，sign1/sign2 双签留痕）
        WriteoffRecord w = new WriteoffRecord();
        w.setWriteoffId(noGen.nextWriteoffNo());
        w.setCardNo(card.getCardNo());
        w.setCustomerId(card.getCustomerId());
        w.setStoreCode(t.getStoreCode());
        w.setProject(t.getProject());
        w.setTimesUsed(1);
        w.setAmount(unit);
        w.setOperator(actor);
        w.setStatus("DONE");
        w.setSign1(actor);
        w.setSign2(reviewer.trim());
        writeoffRepo.save(w);

        t.setStatus(ST_DONE);
        t.setReviewer(reviewer.trim());
        t.setCardNo(card.getCardNo());
        t.setAmount(unit);
        t.setWriteoffId(w.getWriteoffId());
        t.setExecutedAt(OffsetDateTime.now());
        if (remark != null && !remark.isBlank()) {
            t.setNote(remark.trim());
        }
        appendTimeline(t, actor, "双签划扣完成，复核人：" + reviewer.trim()
                + "；扣卡 " + card.getCardNo() + " 1 次/" + unit + " 分，划扣单号 " + w.getWriteoffId());
        WriteoffDeskTask saved = wdRepo.save(t);

        audit.record("WDESK", wdNo, actor, "EXECUTE",
                "{\"card\":\"" + card.getCardNo() + "\",\"writeoffId\":\"" + w.getWriteoffId()
                        + "\",\"reviewer\":\"" + esc(reviewer.trim()) + "\",\"amount\":" + unit
                        + ",\"remainTimes\":" + card.getRemainTimes() + "}");
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
