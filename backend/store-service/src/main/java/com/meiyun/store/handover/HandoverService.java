package com.meiyun.store.handover;

import com.meiyun.security.DataScope;
import com.meiyun.store.Store;
import com.meiyun.store.StoreRepository;
import com.meiyun.store.consumable.ConsumableAuditRecorder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HandoverService {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String CONFIRMED = "CONFIRMED";

    static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    static LocalDate today() {
        return LocalDate.now(BIZ_ZONE);
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(BIZ_ZONE);
    }

    private final HandoverRepository hoRepo;
    private final HandoverTodoRepository todoRepo;
    private final StoreRepository storeRepo;
    private final HjNoGenerator noGen;
    private final ConsumableAuditRecorder audit;

    public HandoverService(HandoverRepository hoRepo,
                           HandoverTodoRepository todoRepo,
                           StoreRepository storeRepo,
                           HjNoGenerator noGen,
                           ConsumableAuditRecorder audit) {
        this.hoRepo = hoRepo;
        this.todoRepo = todoRepo;
        this.storeRepo = storeRepo;
        this.noGen = noGen;
        this.audit = audit;
    }

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "交接班单不存在");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String filterStoreCode, String status) {
        List<Handover> rows;
        if ("__NONE__".equals(filterStoreCode)) {
            rows = List.of();
        } else {
            Specification<Handover> spec = DataScope.storeSpec("storeCode");
            if (filterStoreCode != null && !filterStoreCode.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), filterStoreCode.trim()));
            }
            if (status != null && !status.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status.trim()));
            }
            rows = hoRepo.findAll(spec, Sort.by(Sort.Direction.DESC, "date").and(Sort.by(Sort.Direction.DESC, "id")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Handover h : rows) out.add(toView(h));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        return toView(mustGet(id));
    }

    @Transactional
    public Map<String, Object> create(String storeCode, CreateCmd cmd, String actor) {
        if (cmd == null) throw badReq("请求体不能为空");
        if (storeCode == null || storeCode.isBlank() || "__NONE__".equals(storeCode))
            throw badReq("未获取到当前门店，请稍后重试");
        Store st = storeRepo.findById(storeCode.trim())
                .orElseThrow(() -> badReq("门店不存在：" + storeCode));
        String shift = requireEnum(cmd.shift(), "MORNING", "EVENING", "FULL");
        LocalDate date = parseDate(cmd.date());
        String toName = trim(cmd.toName(), "请填写接班责任人", 64);

        Handover h = new Handover();
        h.setHandoverNo(noGen.nextHoNo());
        h.setStoreCode(st.getStoreCode());
        h.setShift(shift);
        h.setDate(date);
        h.setStatus(DRAFT);
        h.setFromName(actor);
        h.setToName(toName);
        h.setCreatedAt(now());
        h.setTimeline(new ArrayList<>());
        addTimeline(h, "创建交接班草稿", actor, null);
        hoRepo.save(h);
        audit.record("HANDOVER", h.getHandoverNo(), actor, "CREATE", "{}");
        return detail(h.getId());
    }

    @Transactional
    public Map<String, Object> updateDraft(Long id, DraftCmd cmd, String actor) {
        if (cmd == null) throw badReq("请求体不能为空");
        Handover h = mustGet(id);
        requireStatus(h, DRAFT, "仅草稿状态可编辑");
        if (cmd.toName() != null) h.setToName(trim(cmd.toName(), "请填写接班责任人", 64));
        if (cmd.revenueAmount() != null) h.setRevenueAmount(nonNegativeAmount(cmd.revenueAmount()));
        if (cmd.orderCount() != null) h.setOrderCount(nonNegative(cmd.orderCount(), "成交单数"));
        if (cmd.arrivalCount() != null) h.setArrivalCount(nonNegative(cmd.arrivalCount(), "到店人数"));
        if (cmd.importantNote() != null) h.setImportantNote(clip(cmd.importantNote(), 512));
        if (cmd.cashNote() != null) h.setCashNote(clip(cmd.cashNote(), 512));
        if (cmd.equipmentNote() != null) h.setEquipmentNote(clip(cmd.equipmentNote(), 512));
        hoRepo.save(h);
        audit.record("HANDOVER", h.getHandoverNo(), actor, "SAVE", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> addTodo(Long id, TodoCmd cmd, String actor) {
        if (cmd == null) throw badReq("请求体不能为空");
        Handover h = mustGet(id);
        requireStatus(h, DRAFT, "仅草稿状态可编辑待办");
        String content = trim(cmd.content(), "请填写待办内容", 128);
        String kind = requireEnum(cmd.kind(), "CUSTOMER", "TASK", "ISSUE");
        HandoverTodo t = new HandoverTodo();
        t.setHoId(id);
        t.setKind(kind);
        t.setContent(content);
        t.setUrgent(Boolean.TRUE.equals(cmd.urgent()));
        t.setDone(false);
        todoRepo.save(t);
        audit.record("HANDOVER", h.getHandoverNo(), actor, "ADD_TODO",
                "{\"content\":" + jsonStr(content) + "}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> removeTodo(Long id, Long todoId, String actor) {
        Handover h = mustGet(id);
        requireStatus(h, DRAFT, "仅草稿状态可编辑待办");
        HandoverTodo t = mustTodo(id, todoId);
        todoRepo.delete(t);
        audit.record("HANDOVER", h.getHandoverNo(), actor, "REMOVE_TODO", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> toggleTodo(Long id, Long todoId, String actor) {
        Handover h = mustGet(id);
        requireStatus(h, CONFIRMED, "仅已交接单可勾选跟进事项");
        HandoverTodo t = mustTodo(id, todoId);
        t.setDone(!Boolean.TRUE.equals(t.getDone()));
        todoRepo.save(t);
        audit.record("HANDOVER", h.getHandoverNo(), actor,
                Boolean.TRUE.equals(t.getDone()) ? "DONE_TODO" : "REOPEN_TODO", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> submit(Long id, String actor) {
        Handover h = mustGet(id);
        if (SUBMITTED.equals(h.getStatus())) throw unprocessable("交接班单已提交，请勿重复提交");
        requireStatus(h, DRAFT, "仅草稿状态可提交");
        h.setStatus(SUBMITTED);
        h.setSubmittedAt(now());
        addTimeline(h, "提交交接班", actor, null);
        hoRepo.save(h);
        audit.record("HANDOVER", h.getHandoverNo(), actor, "SUBMIT", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> confirm(Long id, ConfirmCmd cmd, String actor) {
        Handover h = mustGet(id);
        requireStatus(h, SUBMITTED, "仅待确认状态可确认接收");
        h.setStatus(CONFIRMED);
        h.setConfirmedAt(now());
        if (cmd != null && cmd.confirmNote() != null) h.setConfirmNote(clip(cmd.confirmNote(), 512));
        addTimeline(h, "确认接收", actor, h.getConfirmNote());
        hoRepo.save(h);
        audit.record("HANDOVER", h.getHandoverNo(), actor, "CONFIRM", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> sendBack(Long id, ConfirmCmd cmd, String actor) {
        Handover h = mustGet(id);
        requireStatus(h, SUBMITTED, "仅待确认状态可退回");
        h.setStatus(DRAFT);
        h.setSubmittedAt(null);
        String note = cmd == null ? null : clip(cmd.confirmNote(), 512);
        addTimeline(h, "退回补充", actor, note);
        hoRepo.save(h);
        audit.record("HANDOVER", h.getHandoverNo(), actor, "SEND_BACK",
                "{\"note\":" + jsonStr(note) + "}");
        return detail(id);
    }

    public record CreateCmd(String shift, String date, String toName) {}

    public record DraftCmd(String toName, Long revenueAmount, Integer orderCount, Integer arrivalCount,
                           String importantNote, String cashNote, String equipmentNote) {}

    public record TodoCmd(String content, String kind, Boolean urgent) {}

    public record ConfirmCmd(String confirmNote) {}

    public record SeedTodo(String content, String kind, boolean done, boolean urgent) {}

    public record SeedHandover(String handoverNo, String storeCode, String shift, LocalDate date, String status,
                               String fromName, String toName, Long revenueAmount, Integer orderCount,
                               Integer arrivalCount, String importantNote, String cashNote, String equipmentNote,
                               String confirmNote, OffsetDateTime submittedAt, OffsetDateTime confirmedAt,
                               OffsetDateTime createdAt, List<Map<String, Object>> timeline,
                               List<SeedTodo> todos) {}

    @Transactional
    public void seed(SeedHandover cmd) {
        Handover h = new Handover();
        h.setHandoverNo(cmd.handoverNo());
        h.setStoreCode(cmd.storeCode());
        h.setShift(cmd.shift());
        h.setDate(cmd.date());
        h.setStatus(cmd.status());
        h.setFromName(cmd.fromName());
        h.setToName(cmd.toName());
        h.setRevenueAmount(cmd.revenueAmount() == null ? 0L : cmd.revenueAmount());
        h.setOrderCount(cmd.orderCount() == null ? 0 : cmd.orderCount());
        h.setArrivalCount(cmd.arrivalCount() == null ? 0 : cmd.arrivalCount());
        h.setImportantNote(cmd.importantNote());
        h.setCashNote(cmd.cashNote());
        h.setEquipmentNote(cmd.equipmentNote());
        h.setConfirmNote(cmd.confirmNote());
        h.setSubmittedAt(cmd.submittedAt());
        h.setConfirmedAt(cmd.confirmedAt());
        h.setCreatedAt(cmd.createdAt() == null ? now() : cmd.createdAt());
        h.setTimeline(cmd.timeline() == null ? List.of() : cmd.timeline());
        hoRepo.save(h);
        if (cmd.todos() != null) {
            for (SeedTodo t : cmd.todos()) {
                HandoverTodo e = new HandoverTodo();
                e.setHoId(h.getId());
                e.setContent(t.content());
                e.setKind(t.kind());
                e.setDone(t.done());
                e.setUrgent(t.urgent());
                todoRepo.save(e);
            }
        }
    }

    private Handover mustGet(Long id) {
        return hoRepo.findById(id).orElseThrow(HandoverService::notFound);
    }

    private HandoverTodo mustTodo(Long hoId, Long todoId) {
        HandoverTodo t = todoRepo.findById(todoId)
                .orElseThrow(() -> badReq("待办不存在"));
        if (!hoId.equals(t.getHoId())) throw badReq("待办不属于该交接班单");
        return t;
    }

    private void requireStatus(Handover h, String expected, String msg) {
        if (!expected.equals(h.getStatus())) throw unprocessable(msg);
    }

    private void addTimeline(Handover h, String action, String by, String detail) {
        List<Map<String, Object>> timeline = new ArrayList<>(h.getTimeline());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("by", by);
        m.put("at", now());
        if (detail != null && !detail.isBlank()) m.put("detail", detail);
        timeline.add(m);
        h.setTimeline(timeline);
    }

    private Map<String, Object> toView(Handover h) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", h.getId());
        row.put("handoverNo", h.getHandoverNo());
        row.put("storeCode", h.getStoreCode());
        row.put("shift", h.getShift());
        row.put("date", h.getDate().toString());
        row.put("status", h.getStatus());
        row.put("fromName", h.getFromName());
        row.put("toName", h.getToName());
        row.put("revenueAmount", h.getRevenueAmount());
        row.put("orderCount", h.getOrderCount());
        row.put("arrivalCount", h.getArrivalCount());

        List<Map<String, Object>> todos = new ArrayList<>();
        for (HandoverTodo t : todoRepo.findByHoIdOrderById(h.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("kind", t.getKind());
            m.put("content", t.getContent());
            m.put("urgent", t.getUrgent());
            m.put("done", t.getDone());
            todos.add(m);
        }
        row.put("todos", todos);
        row.put("importantNote", h.getImportantNote() == null ? "" : h.getImportantNote());
        row.put("cashNote", h.getCashNote() == null ? "" : h.getCashNote());
        row.put("equipmentNote", h.getEquipmentNote() == null ? "" : h.getEquipmentNote());
        row.put("confirmNote", h.getConfirmNote() == null ? "" : h.getConfirmNote());
        row.put("submittedAt", h.getSubmittedAt());
        row.put("confirmedAt", h.getConfirmedAt());
        row.put("createdAt", h.getCreatedAt());
        row.put("timeline", h.getTimeline());
        return row;
    }

    private static LocalDate parseDate(String v) {
        if (v == null || v.isBlank()) throw badReq("请选择交接日期");
        try {
            return LocalDate.parse(v.trim());
        } catch (Exception e) {
            throw badReq("交接日期格式不正确");
        }
    }

    private static String requireEnum(String v, String... allowed) {
        if (v == null || v.isBlank()) throw badReq("枚举值不能为空");
        String s = v.trim();
        for (String a : allowed) if (a.equals(s)) return s;
        throw badReq("不支持的枚举值：" + s);
    }

    private static String trim(String v, String emptyMsg, int max) {
        if (v == null || v.isBlank()) throw badReq(emptyMsg);
        String s = v.trim();
        if (s.length() > max) return s.substring(0, max);
        return s;
    }

    private static int nonNegative(Integer v, String label) {
        if (v < 0) throw badReq(label + "不能为负数");
        return v;
    }

    private static long nonNegativeAmount(Long v) {
        if (v < 0) throw badReq("业绩金额不能为负数");
        return v;
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
