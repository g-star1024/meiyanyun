package com.meiyun.store.workorder;

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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkOrderService {

    public static final String PENDING = "PENDING";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String DONE = "DONE";
    public static final String ESCALATED = "ESCALATED";

    private final WorkOrderRepository woRepo;
    private final WorkOrderNoteRepository noteRepo;
    private final WoNoGenerator noGen;
    private final StoreRepository storeRepo;
    private final ConsumableAuditRecorder audit;

    public WorkOrderService(WorkOrderRepository woRepo,
                            WorkOrderNoteRepository noteRepo,
                            WoNoGenerator noGen,
                            StoreRepository storeRepo,
                            ConsumableAuditRecorder audit) {
        this.woRepo = woRepo;
        this.noteRepo = noteRepo;
        this.noGen = noGen;
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
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String filterStoreCode, String type, String status, String assignee) {
        List<WorkOrder> rows;
        if ("__NONE__".equals(filterStoreCode)) {
            rows = List.of();
        } else {
            Specification<WorkOrder> spec = DataScope.storeSpec("storeCode");
            if (filterStoreCode != null) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), filterStoreCode.trim()));
            }
            if (type != null && !type.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("type"), type.trim()));
            }
            if (status != null && !status.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status.trim()));
            }
            if (assignee != null && !assignee.isBlank()) {
                String kw = "%" + assignee.trim() + "%";
                spec = spec.and((root, q, cb) -> cb.like(root.get("assignee"), kw));
            }
            rows = woRepo.findAll(spec, Sort.by(Sort.Direction.DESC, "id"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkOrder w : rows) out.add(toView(w));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        return toView(mustGet(id));
    }

    @Transactional
    public Map<String, Object> create(String storeCode, String actor, WoCmd cmd) {
        if (cmd == null) throw badReq("请求体不能为空");
        if (storeCode == null || storeCode.isBlank() || "__NONE__".equals(storeCode))
            throw badReq("请指定工单门店");
        Store st = storeRepo.findById(storeCode.trim())
                .orElseThrow(() -> badReq("工单门店不存在：" + storeCode));
        String type = requireEnum(cmd.type(), "REPAIR", "INSPECTION", "CUSTOMER", "CONSULT");
        String title = trim(cmd.title(), "请填写工单标题", 128);
        String description = trim(cmd.description(), "请填写工单描述", 512);
        String priority = cmd.priority() == null || cmd.priority().isBlank()
                ? "MEDIUM" : requireEnum(cmd.priority(), "HIGH", "MEDIUM", "LOW");
        OffsetDateTime deadline;
        if (cmd.deadline() == null || cmd.deadline().isBlank()) {
            deadline = OffsetDateTime.now().plusHours(4);
        } else {
            try {
                deadline = OffsetDateTime.parse(cmd.deadline().trim());
            } catch (Exception e) {
                throw badReq("截止时间格式不正确");
            }
        }
        String assignee = blankToNull(cmd.assignee());
        if (assignee == null) assignee = "待分配";

        WorkOrder w = new WorkOrder();
        w.setWoNo(noGen.nextWoNo());
        w.setStoreCode(st.getStoreCode());
        w.setType(type);
        w.setTitle(title);
        w.setDescription(description);
        w.setCustomerName(clip(blankToNull(cmd.customerName()), 64));
        w.setProject(clip(blankToNull(cmd.project()), 64));
        w.setRoom(clip(blankToNull(cmd.room()), 64));
        w.setAssignee(assignee);
        w.setStatus(PENDING);
        w.setPriority(priority);
        w.setDeadline(deadline);
        woRepo.save(w);
        addNote(w.getId(), actor, "创建工单");

        audit.record("WORK_ORDER", w.getWoNo(), actor, "CREATE",
                "{\"storeCode\":" + jsonStr(w.getStoreCode())
                        + ",\"type\":" + jsonStr(w.getType())
                        + ",\"title\":" + jsonStr(w.getTitle())
                        + ",\"assignee\":" + jsonStr(w.getAssignee()) + "}");
        return detail(w.getId());
    }

    @Transactional
    public Map<String, Object> start(Long id, String actor) {
        WorkOrder w = mustGet(id);
        if (!PENDING.equals(w.getStatus())) throw unprocessable("仅待服务工单可开始处理");
        w.setStatus(IN_PROGRESS);
        w.setStartedAt(OffsetDateTime.now());
        woRepo.save(w);
        addNote(id, actor, "开始处理");
        audit.record("WORK_ORDER", w.getWoNo(), actor, "START", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> complete(Long id, String actor, String note) {
        WorkOrder w = mustGet(id);
        if (!IN_PROGRESS.equals(w.getStatus()) && !ESCALATED.equals(w.getStatus()))
            throw unprocessable("仅处理中或已升级工单可完成关闭");
        w.setStatus(DONE);
        w.setCompletedAt(OffsetDateTime.now());
        woRepo.save(w);
        String content = (note == null || note.isBlank()) ? "已完成" : "完成：" + note.trim();
        addNote(id, actor, content);
        audit.record("WORK_ORDER", w.getWoNo(), actor, "COMPLETE",
                "{\"note\":" + jsonStr(blankToNull(note)) + "}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> escalate(Long id, String actor, String reason) {
        WorkOrder w = mustGet(id);
        if (DONE.equals(w.getStatus())) throw unprocessable("已完成工单不可升级");
        if (reason == null || reason.isBlank()) throw badReq("请填写升级原因");
        w.setStatus(ESCALATED);
        woRepo.save(w);
        addNote(id, actor, "升级：" + reason.trim());
        audit.record("WORK_ORDER", w.getWoNo(), actor, "ESCALATE",
                "{\"reason\":" + jsonStr(reason.trim()) + "}");
        return detail(id);
    }

    @Transactional
    public void addNote(Long id, String actor, String content) {
        mustGet(id);
        if (content == null || content.isBlank()) throw badReq("备注内容不能为空");
        WorkOrderNote n = new WorkOrderNote();
        n.setWoId(id);
        n.setNoteBy(actor);
        n.setContent(content.trim());
        n.setNoteAt(OffsetDateTime.now());
        noteRepo.save(n);
    }

    public record WoCmd(String storeCode, String type, String title, String description,
                        String customerName, String project, String room, String assignee,
                        String priority, String deadline) {}

    public record SeedNote(String by, String text, OffsetDateTime at) {}

    public record SeedCmd(String woNo, String type, String title, String description,
                          String customerName, String project, String room, String assignee,
                          String status, String priority, OffsetDateTime deadline,
                          OffsetDateTime createdAt, OffsetDateTime startedAt,
                          OffsetDateTime completedAt, List<SeedNote> notes) {}

    @Transactional
    public void seed(SeedCmd cmd) {
        WorkOrder w = new WorkOrder();
        w.setWoNo(cmd.woNo());
        w.setStoreCode("SST01");
        w.setType(cmd.type());
        w.setTitle(cmd.title());
        w.setDescription(cmd.description());
        w.setCustomerName(cmd.customerName());
        w.setProject(cmd.project());
        w.setRoom(cmd.room());
        w.setAssignee(cmd.assignee());
        w.setStatus(cmd.status());
        w.setPriority(cmd.priority());
        w.setDeadline(cmd.deadline());
        w.setCreatedAt(cmd.createdAt());
        w.setStartedAt(cmd.startedAt());
        w.setCompletedAt(cmd.completedAt());
        woRepo.save(w);
        for (SeedNote n : cmd.notes()) {
            WorkOrderNote en = new WorkOrderNote();
            en.setWoId(w.getId());
            en.setNoteBy(n.by());
            en.setContent(n.text());
            en.setNoteAt(n.at());
            noteRepo.save(en);
        }
    }

    private WorkOrder mustGet(Long id) {
        return woRepo.findById(id).orElseThrow(WorkOrderService::notFound);
    }

    private Map<String, Object> toView(WorkOrder w) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", w.getId());
        row.put("woNo", w.getWoNo());
        row.put("storeCode", w.getStoreCode());
        row.put("type", w.getType());
        row.put("title", w.getTitle());
        row.put("description", w.getDescription());
        row.put("customerName", w.getCustomerName());
        row.put("project", w.getProject());
        row.put("room", w.getRoom());
        row.put("assignee", w.getAssignee());
        row.put("status", w.getStatus());
        row.put("priority", w.getPriority());
        row.put("deadline", w.getDeadline());
        row.put("createdAt", w.getCreatedAt());
        row.put("startedAt", w.getStartedAt());
        row.put("completedAt", w.getCompletedAt());

        List<Map<String, Object>> notes = new ArrayList<>();
        for (WorkOrderNote n : noteRepo.findByWoIdOrderByNoteAtDesc(w.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("by", n.getNoteBy());
            m.put("text", n.getContent());
            m.put("at", n.getNoteAt());
            notes.add(m);
        }
        row.put("notes", notes);
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

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
