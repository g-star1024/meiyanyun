package com.meiyun.store.daily;

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
public class DailyService {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";

    public static final int HOUR_COUNT = 12;

    static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    static LocalDate today() {
        return LocalDate.now(BIZ_ZONE);
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(BIZ_ZONE);
    }

    private final DailyReportRepository reportRepo;
    private final DailyTodoRepository todoRepo;
    private final StoreRepository storeRepo;
    private final ConsumableAuditRecorder audit;

    public DailyService(DailyReportRepository reportRepo,
                        DailyTodoRepository todoRepo,
                        StoreRepository storeRepo,
                        ConsumableAuditRecorder audit) {
        this.reportRepo = reportRepo;
        this.todoRepo = todoRepo;
        this.storeRepo = storeRepo;
        this.audit = audit;
    }

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "日报不存在");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String filterStoreCode, String status) {
        List<DailyReport> rows;
        if ("__NONE__".equals(filterStoreCode)) {
            rows = List.of();
        } else {
            Specification<DailyReport> spec = DataScope.storeSpec("storeCode");
            if (filterStoreCode != null && !filterStoreCode.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), filterStoreCode.trim()));
            }
            if (status != null && !status.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status.trim()));
            }
            rows = reportRepo.findAll(spec, Sort.by(Sort.Direction.DESC, "date"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (DailyReport r : rows) out.add(toView(r));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        return toView(mustGet(id));
    }

    @Transactional
    public Map<String, Object> today(String storeCode, String actor) {
        if (storeCode == null || storeCode.isBlank() || "__NONE__".equals(storeCode))
            throw badReq("未获取到当前门店，请稍后重试");
        Store st = storeRepo.findById(storeCode.trim())
                .orElseThrow(() -> badReq("门店不存在：" + storeCode));
        LocalDate today = today();
        DailyReport r = reportRepo.findByStoreCodeAndDate(st.getStoreCode(), today).orElse(null);
        if (r == null) {
            r = newBlank(st.getStoreCode(), today);
            addTimeline(r, "创建今日日报草稿", actor);
            reportRepo.save(r);
        }
        return detail(r.getId());
    }

    @Transactional
    public Map<String, Object> saveFields(Long id, FieldsCmd cmd, String actor) {
        if (cmd == null) throw badReq("请求体不能为空");
        DailyReport r = mustGet(id);
        requireDraft(r);
        if (cmd.footfall() != null) r.setFootfall(nonNegative(cmd.footfall(), "今日客流"));
        if (cmd.orders() != null) r.setOrders(nonNegative(cmd.orders(), "今日成交"));
        if (cmd.services() != null) r.setServices(nonNegative(cmd.services(), "服务工单完成"));
        if (cmd.inventoryAlerts() != null) r.setInventoryAlerts(nonNegative(cmd.inventoryAlerts(), "库存预警"));
        if (cmd.exceptions() != null) r.setExceptions(clip(cmd.exceptions(), 512));
        if (cmd.note() != null) r.setNote(clip(cmd.note(), 512));
        reportRepo.save(r);
        audit.record("DAILY_REPORT", dailyNo(r), actor, "SAVE", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> saveHourly(Long id, HourlyCmd cmd, String actor) {
        if (cmd == null || cmd.hourly() == null) throw badReq("请提供分时客流数据");
        DailyReport r = mustGet(id);
        requireDraft(r);
        List<Integer> hourly = new ArrayList<>();
        for (int i = 0; i < HOUR_COUNT; i++) {
            Integer v = i < cmd.hourly().size() ? cmd.hourly().get(i) : 0;
            hourly.add(nonNegative(v == null ? 0 : v, "分时客流"));
        }
        r.setHourly(hourly);
        r.setFootfall(hourly.stream().mapToInt(Integer::intValue).sum());
        reportRepo.save(r);
        audit.record("DAILY_REPORT", dailyNo(r), actor, "SAVE_HOURLY", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> addTodo(Long id, TodoCmd cmd, String actor) {
        if (cmd == null) throw badReq("请求体不能为空");
        DailyReport r = mustGet(id);
        requireDraft(r);
        String content = trim(cmd.content(), "请填写待办内容", 128);
        String kind = requireEnum(cmd.kind(), "TASK", "CUSTOMER", "ISSUE");
        DailyTodo t = new DailyTodo();
        t.setDrId(id);
        t.setContent(content);
        t.setKind(kind);
        t.setDone(false);
        t.setUrgent(Boolean.TRUE.equals(cmd.urgent()));
        todoRepo.save(t);
        audit.record("DAILY_REPORT", dailyNo(r), actor, "ADD_TODO",
                "{\"content\":" + jsonStr(content) + "}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> toggleTodo(Long id, Long todoId, String actor) {
        DailyReport r = mustGet(id);
        requireDraft(r);
        DailyTodo t = mustTodo(id, todoId);
        t.setDone(!Boolean.TRUE.equals(t.getDone()));
        todoRepo.save(t);
        audit.record("DAILY_REPORT", dailyNo(r), actor,
                Boolean.TRUE.equals(t.getDone()) ? "DONE_TODO" : "REOPEN_TODO", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> removeTodo(Long id, Long todoId, String actor) {
        DailyReport r = mustGet(id);
        requireDraft(r);
        DailyTodo t = mustTodo(id, todoId);
        todoRepo.delete(t);
        audit.record("DAILY_REPORT", dailyNo(r), actor, "REMOVE_TODO", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> submit(Long id, String actor) {
        DailyReport r = mustGet(id);
        if (SUBMITTED.equals(r.getStatus())) throw unprocessable("日报已提交，请勿重复提交");
        r.setStatus(SUBMITTED);
        r.setSubmittedBy(actor);
        r.setSubmittedAt(now());
        addTimeline(r, "提交门店日报", actor);
        reportRepo.save(r);
        audit.record("DAILY_REPORT", dailyNo(r), actor, "SUBMIT", "{}");
        return detail(id);
    }

    public record FieldsCmd(Integer footfall, Integer orders, Integer services,
                            Integer inventoryAlerts, String exceptions, String note) {}

    public record HourlyCmd(List<Integer> hourly) {}

    public record TodoCmd(String content, String kind, Boolean urgent) {}

    public record SeedTodo(String content, String kind, boolean done, boolean urgent) {}

    public record SeedReport(String storeCode, LocalDate date, String status,
                             Integer footfall, Integer orders, Integer services,
                             Integer inventoryAlerts, List<Integer> hourly,
                             String exceptions, String note, String submittedBy,
                             OffsetDateTime submittedAt,
                             List<Map<String, Object>> timeline, List<SeedTodo> todos) {}

    @Transactional
    public void seed(SeedReport cmd) {
        DailyReport r = new DailyReport();
        r.setStoreCode(cmd.storeCode());
        r.setDate(cmd.date());
        r.setStatus(cmd.status());
        r.setFootfall(cmd.footfall() == null ? 0 : cmd.footfall());
        r.setOrders(cmd.orders() == null ? 0 : cmd.orders());
        r.setServices(cmd.services() == null ? 0 : cmd.services());
        r.setInventoryAlerts(cmd.inventoryAlerts() == null ? 0 : cmd.inventoryAlerts());
        r.setHourly(cmd.hourly() == null ? List.of() : cmd.hourly());
        r.setExceptions(cmd.exceptions());
        r.setNote(cmd.note());
        r.setSubmittedBy(cmd.submittedBy());
        r.setSubmittedAt(cmd.submittedAt());
        r.setTimeline(cmd.timeline() == null ? List.of() : cmd.timeline());
        reportRepo.save(r);
        if (cmd.todos() != null) {
            for (SeedTodo t : cmd.todos()) {
                DailyTodo e = new DailyTodo();
                e.setDrId(r.getId());
                e.setContent(t.content());
                e.setKind(t.kind());
                e.setDone(t.done());
                e.setUrgent(t.urgent());
                todoRepo.save(e);
            }
        }
    }

    private DailyReport newBlank(String storeCode, LocalDate date) {
        DailyReport r = new DailyReport();
        r.setStoreCode(storeCode);
        r.setDate(date);
        r.setStatus(DRAFT);
        r.setFootfall(0);
        r.setOrders(0);
        r.setServices(0);
        r.setInventoryAlerts(0);
        r.setHourly(new ArrayList<>(java.util.Collections.nCopies(HOUR_COUNT, 0)));
        r.setTimeline(new ArrayList<>());
        return r;
    }

    private void addTimeline(DailyReport r, String action, String by) {
        List<Map<String, Object>> timeline = new ArrayList<>(r.getTimeline());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("by", by);
        m.put("at", now());
        timeline.add(m);
        r.setTimeline(timeline);
    }

    private DailyReport mustGet(Long id) {
        return reportRepo.findById(id).orElseThrow(DailyService::notFound);
    }

    private DailyTodo mustTodo(Long drId, Long todoId) {
        DailyTodo t = todoRepo.findById(todoId)
                .orElseThrow(() -> badReq("待办不存在"));
        if (!drId.equals(t.getDrId())) throw badReq("待办不属于该日报");
        return t;
    }

    private void requireDraft(DailyReport r) {
        if (SUBMITTED.equals(r.getStatus()))
            throw unprocessable("日报已提交并锁定，不可修改");
    }

    private static String dailyNo(DailyReport r) {
        return "DR-" + r.getDate().toString().replace("-", "");
    }

    private Map<String, Object> toView(DailyReport r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("dailyNo", dailyNo(r));
        row.put("storeCode", r.getStoreCode());
        row.put("date", r.getDate().toString());
        row.put("status", r.getStatus());
        row.put("footfall", r.getFootfall());
        row.put("orders", r.getOrders());
        row.put("services", r.getServices());
        row.put("inventoryAlerts", r.getInventoryAlerts());

        List<Integer> hourly = new ArrayList<>(r.getHourly());
        while (hourly.size() < HOUR_COUNT) hourly.add(0);
        row.put("hourly", hourly);

        List<Map<String, Object>> todos = new ArrayList<>();
        for (DailyTodo t : todoRepo.findByDrIdOrderById(r.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("content", t.getContent());
            m.put("kind", t.getKind());
            m.put("done", t.getDone());
            m.put("urgent", t.getUrgent());
            todos.add(m);
        }
        row.put("todos", todos);
        row.put("exceptions", r.getExceptions() == null ? "" : r.getExceptions());
        row.put("note", r.getNote() == null ? "" : r.getNote());
        row.put("submittedBy", r.getSubmittedBy());
        row.put("submittedAt", r.getSubmittedAt());
        row.put("timeline", r.getTimeline());
        return row;
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

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
