package com.meiyun.store.wastage;

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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WastageService {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTING = "SUBMITTING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private static final Set<String> REASONS = Set.of("BROKEN", "EXPIRED", "INVENTORY_LOSS", "OTHER");

    private final WastageRepository wsRepo;
    private final WastageNoteRepository noteRepo;
    private final WsNoGenerator noGen;
    private final StoreRepository storeRepo;
    private final ConsumableAuditRecorder audit;

    public WastageService(WastageRepository wsRepo,
                          WastageNoteRepository noteRepo,
                          WsNoGenerator noGen,
                          StoreRepository storeRepo,
                          ConsumableAuditRecorder audit) {
        this.wsRepo = wsRepo;
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
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "报损单不存在");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String filterStoreCode, String status, String reason,
                                          String reporter) {
        List<Wastage> rows;
        if ("__NONE__".equals(filterStoreCode)) {
            rows = List.of();
        } else {
            Specification<Wastage> spec = DataScope.storeSpec("storeCode");
            if (filterStoreCode != null) {
                String fsc = filterStoreCode.trim();
                spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), fsc));
            }
            if (status != null && !status.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status.trim()));
            }
            if (reason != null && !reason.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("reason"), reason.trim()));
            }
            if (reporter != null && !reporter.isBlank()) {
                String kw = "%" + reporter.trim() + "%";
                spec = spec.and((root, q, cb) -> cb.like(root.get("reporter"), kw));
            }
            rows = wsRepo.findAll(spec, Sort.by(Sort.Direction.DESC, "occurredAt", "id"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Wastage w : rows) out.add(toView(w));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Wastage w = mustGet(id);
        return toView(w);
    }

    @Transactional
    public Map<String, Object> create(String storeCode, String itemName, String spec, Integer qty,
                                      String unit, Long amountFen, String reason, String reporter,
                                      String location, String description,
                                      OffsetDateTime occurredAt, String actor) {
        if (storeCode == null || storeCode.isBlank()) throw badReq("请指定报损门店");
        Store st = storeRepo.findById(storeCode.trim())
                .orElseThrow(() -> badReq("报损门店不存在：" + storeCode));
        if (itemName == null || itemName.isBlank()) throw badReq("请填写物品名称");
        if (qty == null || qty <= 0) throw badReq("报损数量必须大于 0");
        if (unit == null || unit.isBlank()) throw badReq("请填写单位");
        if (amountFen == null || amountFen < 0) throw badReq("报损金额不合法");
        if (reason == null || !REASONS.contains(reason.trim())) throw badReq("请选择报损原因");
        if (reporter == null || reporter.isBlank()) throw badReq("请填写报损人");

        Wastage w = new Wastage();
        w.setWsNo(noGen.nextWsNo());
        w.setStoreCode(st.getStoreCode());
        w.setStatus(DRAFT);
        w.setReason(reason.trim());
        w.setItemName(itemName.trim());
        w.setSpec(blankToNull(spec));
        w.setQty(qty);
        w.setUnit(unit.trim());
        w.setAmountFen(amountFen);
        w.setReporter(reporter.trim());
        w.setLocation(blankToNull(location));
        w.setDescription(blankToNull(description));
        w.setOccurredAt(occurredAt == null ? OffsetDateTime.now() : occurredAt);
        wsRepo.save(w);
        addNote(w.getId(), actor, "创建报损单（草稿）");

        audit.record("LOSS_REPORT", w.getWsNo(), actor, "CREATE",
                "{\"storeCode\":" + jsonStr(w.getStoreCode())
                        + ",\"itemName\":" + jsonStr(w.getItemName())
                        + ",\"qty\":" + qty
                        + ",\"amountFen\":" + amountFen
                        + ",\"reason\":" + jsonStr(w.getReason()) + "}");
        return detail(w.getId());
    }

    @Transactional
    public Map<String, Object> submit(Long id, String actor) {
        Wastage w = mustGet(id);
        if (!DRAFT.equals(w.getStatus())) throw unprocessable("仅草稿状态可提交审批");
        w.setStatus(SUBMITTING);
        wsRepo.save(w);
        addNote(id, actor, "提交审批");
        audit.record("LOSS_REPORT", w.getWsNo(), actor, "SUBMIT", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> approve(Long id, String actor, String note) {
        Wastage w = mustGet(id);
        if (!SUBMITTING.equals(w.getStatus())) throw unprocessable("仅待审批状态可审批");
        w.setStatus(APPROVED);
        w.setApprover(actor);
        w.setApprovedAt(OffsetDateTime.now());
        wsRepo.save(w);
        String content = (note == null || note.isBlank()) ? "审批通过" : "审批通过：" + note.trim();
        addNote(id, actor, content);
        audit.record("LOSS_REPORT", w.getWsNo(), actor, "APPROVE",
                "{\"note\":" + jsonStr(blankToNull(note)) + "}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> reject(Long id, String actor, String reason) {
        Wastage w = mustGet(id);
        if (!SUBMITTING.equals(w.getStatus())) throw unprocessable("仅待审批状态可驳回");
        if (reason == null || reason.isBlank()) throw badReq("请填写驳回原因");
        w.setStatus(REJECTED);
        w.setApprover(actor);
        w.setApprovedAt(OffsetDateTime.now());
        w.setRejectReason(reason.trim());
        wsRepo.save(w);
        addNote(id, actor, "驳回：" + reason.trim());
        audit.record("LOSS_REPORT", w.getWsNo(), actor, "REJECT",
                "{\"reason\":" + jsonStr(reason.trim()) + "}");
        return detail(id);
    }

    @Transactional
    public void addNote(Long id, String actor, String content) {
        mustGet(id);
        if (content == null || content.isBlank()) throw badReq("备注内容不能为空");
        WastageNote n = new WastageNote();
        n.setWsId(id);
        n.setNoteBy(actor);
        n.setContent(content.trim());
        n.setNoteAt(OffsetDateTime.now());
        noteRepo.save(n);
    }

    public record SeedNote(String by, String text, OffsetDateTime at) {}

    public record SeedCmd(String wsNo, String status, String reason, String itemName, String spec,
                          int qty, String unit, long amountFen, String reporter, String location,
                          String description, String approver, String rejectReason,
                          OffsetDateTime occurredAt, OffsetDateTime approvedAt,
                          OffsetDateTime createdAt, List<SeedNote> notes) {}

    @Transactional
    public void seed(SeedCmd cmd) {
        Wastage w = new Wastage();
        w.setWsNo(cmd.wsNo());
        w.setStoreCode("SST01");
        w.setStatus(cmd.status());
        w.setReason(cmd.reason());
        w.setItemName(cmd.itemName());
        w.setSpec(cmd.spec());
        w.setQty(cmd.qty());
        w.setUnit(cmd.unit());
        w.setAmountFen(cmd.amountFen());
        w.setReporter(cmd.reporter());
        w.setLocation(cmd.location());
        w.setDescription(cmd.description());
        w.setApprover(cmd.approver());
        w.setRejectReason(cmd.rejectReason());
        w.setOccurredAt(cmd.occurredAt());
        w.setApprovedAt(cmd.approvedAt());
        w.setCreatedAt(cmd.createdAt());
        wsRepo.save(w);

        for (SeedNote n : cmd.notes()) {
            WastageNote en = new WastageNote();
            en.setWsId(w.getId());
            en.setNoteBy(n.by());
            en.setContent(n.text());
            en.setNoteAt(n.at());
            noteRepo.save(en);
        }
    }

    private Wastage mustGet(Long id) {
        return wsRepo.findById(id).orElseThrow(WastageService::notFound);
    }

    private Map<String, Object> toView(Wastage w) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", w.getId());
        row.put("wsNo", w.getWsNo());
        row.put("storeCode", w.getStoreCode());
        row.put("status", w.getStatus());
        row.put("reason", w.getReason());
        row.put("itemName", w.getItemName());
        row.put("spec", w.getSpec());
        row.put("qty", w.getQty());
        row.put("unit", w.getUnit());
        row.put("amountYuan", toYuan(w.getAmountFen()));
        row.put("reporter", w.getReporter());
        row.put("location", w.getLocation());
        row.put("description", w.getDescription());
        row.put("approver", w.getApprover());
        row.put("rejectReason", w.getRejectReason());
        row.put("occurredAt", w.getOccurredAt());
        row.put("approvedAt", w.getApprovedAt());
        row.put("createdAt", w.getCreatedAt());

        List<Map<String, Object>> notes = new ArrayList<>();
        for (WastageNote n : noteRepo.findByWsIdOrderByNoteAtDesc(w.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("by", n.getNoteBy());
            m.put("text", n.getContent());
            m.put("at", n.getNoteAt());
            notes.add(m);
        }
        row.put("notes", notes);
        return row;
    }

    private static double toYuan(Long fen) {
        if (fen == null) return 0d;
        return BigDecimal.valueOf(fen).movePointLeft(2).doubleValue();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
