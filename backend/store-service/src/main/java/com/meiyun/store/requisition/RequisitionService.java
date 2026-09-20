package com.meiyun.store.requisition;

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
public class RequisitionService {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTING = "SUBMITTING";
    public static final String APPROVED = "APPROVED";
    public static final String RECEIVED = "RECEIVED";
    public static final String REJECTED = "REJECTED";

    private final RequisitionRepository rqRepo;
    private final RequisitionItemRepository itemRepo;
    private final RequisitionNoteRepository noteRepo;
    private final RqNoGenerator noGen;
    private final StoreRepository storeRepo;
    private final ConsumableAuditRecorder audit;

    public RequisitionService(RequisitionRepository rqRepo,
                              RequisitionItemRepository itemRepo,
                              RequisitionNoteRepository noteRepo,
                              RqNoGenerator noGen,
                              StoreRepository storeRepo,
                              ConsumableAuditRecorder audit) {
        this.rqRepo = rqRepo;
        this.itemRepo = itemRepo;
        this.noteRepo = noteRepo;
        this.noGen = noGen;
        this.storeRepo = storeRepo;
        this.audit = audit;
    }

    public record RqLineCmd(String name, String spec, Integer qty, String unit) {}

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "申领单不存在");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String filterStoreCode, String status, String applicant) {
        List<Requisition> rows;
        if ("__NONE__".equals(filterStoreCode)) {
            rows = List.of();
        } else {
            Specification<Requisition> spec = DataScope.storeSpec("storeCode");
            if (filterStoreCode != null) {
                String fsc = filterStoreCode.trim();
                spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), fsc));
            }
            if (status != null && !status.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status.trim()));
            }
            if (applicant != null && !applicant.isBlank()) {
                String kw = "%" + applicant.trim() + "%";
                spec = spec.and((root, q, cb) -> cb.like(root.get("applicant"), kw));
            }
            rows = rqRepo.findAll(spec, Sort.by(Sort.Direction.DESC, "id"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Requisition r : rows) out.add(toView(r));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Requisition r = mustGet(id);
        return toView(r);
    }

    @Transactional
    public Map<String, Object> create(String storeCode, String applicant, String purpose, String remark,
                                      String actor, List<RqLineCmd> lines) {
        if (storeCode == null || storeCode.isBlank()) throw badReq("请指定申领门店");
        Store st = storeRepo.findById(storeCode.trim())
                .orElseThrow(() -> badReq("申领门店不存在：" + storeCode));
        if (applicant == null || applicant.isBlank()) throw badReq("请填写申请人");
        if (purpose == null || purpose.isBlank()) throw badReq("请填写申领用途");
        List<RqLineCmd> safe = normalizeLines(lines);

        Requisition r = new Requisition();
        r.setRqNo(noGen.nextRqNo());
        r.setStoreCode(st.getStoreCode());
        r.setStatus(DRAFT);
        r.setApplicant(applicant.trim());
        r.setPurpose(purpose.trim());
        r.setRemark(blankToNull(remark));
        rqRepo.save(r);
        saveLines(r.getId(), safe);
        addNote(r.getId(), actor, "创建申领单（草稿）");

        audit.record("REQUISITION", r.getRqNo(), actor, "CREATE",
                "{\"storeCode\":" + jsonStr(r.getStoreCode())
                        + ",\"applicant\":" + jsonStr(r.getApplicant())
                        + ",\"purpose\":" + jsonStr(r.getPurpose())
                        + ",\"lineCount\":" + safe.size() + "}");
        return detail(r.getId());
    }

    @Transactional
    public Map<String, Object> submit(Long id, String actor) {
        Requisition r = mustGet(id);
        if (!DRAFT.equals(r.getStatus())) throw unprocessable("仅草稿状态可提交审批");
        r.setStatus(SUBMITTING);
        rqRepo.save(r);
        addNote(id, actor, "提交审批");
        audit.record("REQUISITION", r.getRqNo(), actor, "SUBMIT", "{}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> approve(Long id, String actor, String note) {
        Requisition r = mustGet(id);
        if (!SUBMITTING.equals(r.getStatus())) throw unprocessable("仅审批中状态可审批");
        r.setStatus(APPROVED);
        r.setApprover(actor);
        r.setApprovedAt(OffsetDateTime.now());
        rqRepo.save(r);
        String content = (note == null || note.isBlank()) ? "审批通过" : "审批通过：" + note.trim();
        addNote(id, actor, content);
        audit.record("REQUISITION", r.getRqNo(), actor, "APPROVE",
                "{\"note\":" + jsonStr(blankToNull(note)) + "}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> reject(Long id, String actor, String reason) {
        Requisition r = mustGet(id);
        if (!SUBMITTING.equals(r.getStatus())) throw unprocessable("仅审批中状态可驳回");
        if (reason == null || reason.isBlank()) throw badReq("请填写驳回原因");
        r.setStatus(REJECTED);
        r.setApprover(actor);
        r.setApprovedAt(OffsetDateTime.now());
        r.setRejectReason(reason.trim());
        rqRepo.save(r);
        addNote(id, actor, "驳回：" + reason.trim());
        audit.record("REQUISITION", r.getRqNo(), actor, "REJECT",
                "{\"reason\":" + jsonStr(reason.trim()) + "}");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> receive(Long id, String actor) {
        Requisition r = mustGet(id);
        if (!APPROVED.equals(r.getStatus())) throw unprocessable("仅待签收状态可签收");
        r.setStatus(RECEIVED);
        r.setReceiver(actor);
        r.setReceivedAt(OffsetDateTime.now());
        rqRepo.save(r);
        addNote(id, actor, "已签收物料");
        audit.record("REQUISITION", r.getRqNo(), actor, "RECEIVE", "{}");
        return detail(id);
    }

    @Transactional
    public void addNote(Long id, String actor, String content) {
        mustGet(id);
        if (content == null || content.isBlank()) throw badReq("备注内容不能为空");
        RequisitionNote n = new RequisitionNote();
        n.setRqId(id);
        n.setNoteBy(actor);
        n.setContent(content.trim());
        n.setNoteAt(OffsetDateTime.now());
        noteRepo.save(n);
    }

    public record SeedLine(String name, String spec, int qty, String unit) {}

    public record SeedNote(String by, String text, OffsetDateTime at) {}

    public record SeedCmd(String rqNo, String applicant, String purpose, String remark,
                          OffsetDateTime createdAt, String status, String approver,
                          OffsetDateTime approvedAt, String receiver, OffsetDateTime receivedAt,
                          String rejectReason, List<SeedLine> lines, List<SeedNote> notes) {}

    @Transactional
    public void seed(SeedCmd cmd) {
        Requisition r = new Requisition();
        r.setRqNo(cmd.rqNo());
        r.setStoreCode("SST01");
        r.setStatus(cmd.status());
        r.setApplicant(cmd.applicant());
        r.setPurpose(cmd.purpose());
        r.setRemark(cmd.remark());
        r.setApprover(cmd.approver());
        r.setApprovedAt(cmd.approvedAt());
        r.setReceiver(cmd.receiver());
        r.setReceivedAt(cmd.receivedAt());
        r.setRejectReason(cmd.rejectReason());
        r.setCreatedAt(cmd.createdAt());
        rqRepo.save(r);

        int lineNo = 1;
        for (SeedLine l : cmd.lines()) {
            RequisitionItem it = new RequisitionItem();
            it.setRqId(r.getId());
            it.setLineNo(lineNo++);
            it.setName(l.name());
            it.setSpec(l.spec());
            it.setQty(l.qty());
            it.setUnit(l.unit());
            itemRepo.save(it);
        }
        for (SeedNote n : cmd.notes()) {
            RequisitionNote en = new RequisitionNote();
            en.setRqId(r.getId());
            en.setNoteBy(n.by());
            en.setContent(n.text());
            en.setNoteAt(n.at());
            noteRepo.save(en);
        }
    }

    private Requisition mustGet(Long id) {
        return rqRepo.findById(id).orElseThrow(RequisitionService::notFound);
    }

    private List<RqLineCmd> normalizeLines(List<RqLineCmd> lines) {
        if (lines == null || lines.isEmpty()) throw badReq("请至少添加一条申领物料");
        List<RqLineCmd> safe = new ArrayList<>();
        for (RqLineCmd l : lines) {
            if (l == null || l.name() == null || l.name().isBlank())
                throw badReq("物料名称不能为空");
            if (l.qty() == null || l.qty() <= 0) throw badReq("申领数量必须大于 0");
            if (l.unit() == null || l.unit().isBlank()) throw badReq("物料单位不能为空");
            safe.add(new RqLineCmd(l.name().trim(), blankToNull(l.spec()), l.qty(), l.unit().trim()));
        }
        return safe;
    }

    private void saveLines(Long rqId, List<RqLineCmd> lines) {
        int lineNo = 1;
        for (RqLineCmd l : lines) {
            RequisitionItem it = new RequisitionItem();
            it.setRqId(rqId);
            it.setLineNo(lineNo++);
            it.setName(l.name());
            it.setSpec(l.spec());
            it.setQty(l.qty());
            it.setUnit(l.unit());
            itemRepo.save(it);
        }
    }

    private Map<String, Object> toView(Requisition r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("rqNo", r.getRqNo());
        row.put("storeCode", r.getStoreCode());
        row.put("status", r.getStatus());
        row.put("applicant", r.getApplicant());
        row.put("purpose", r.getPurpose());
        row.put("remark", r.getRemark());
        row.put("approver", r.getApprover());
        row.put("receiver", r.getReceiver());
        row.put("rejectReason", r.getRejectReason());
        row.put("approvedAt", r.getApprovedAt());
        row.put("receivedAt", r.getReceivedAt());
        row.put("createdAt", r.getCreatedAt());

        List<Map<String, Object>> items = new ArrayList<>();
        for (RequisitionItem it : itemRepo.findByRqIdOrderByLineNoAsc(r.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", it.getName());
            m.put("spec", it.getSpec());
            m.put("qty", it.getQty());
            m.put("unit", it.getUnit());
            items.add(m);
        }
        row.put("items", items);

        List<Map<String, Object>> notes = new ArrayList<>();
        for (RequisitionNote n : noteRepo.findByRqIdOrderByNoteAtDesc(r.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("by", n.getNoteBy());
            m.put("text", n.getContent());
            m.put("at", n.getNoteAt());
            notes.add(m);
        }
        row.put("notes", notes);
        return row;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
