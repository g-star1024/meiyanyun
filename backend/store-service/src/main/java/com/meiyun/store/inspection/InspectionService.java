package com.meiyun.store.inspection;

import com.meiyun.security.DataScope;
import com.meiyun.store.consumable.ConsumableAuditRecorder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class InspectionService {

    static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Set<String> TYPES = Set.of("ENV", "SERVICE", "COMPLIANCE");

    private final InspectionRepository repo;
    private final InspectionItemRepository itemRepo;
    private final RectifyIssueRepository issueRepo;
    private final InsNoGenerator noGen;
    private final ConsumableAuditRecorder audit;

    public InspectionService(InspectionRepository repo,
                             InspectionItemRepository itemRepo,
                             RectifyIssueRepository issueRepo,
                             InsNoGenerator noGen,
                             ConsumableAuditRecorder audit) {
        this.repo = repo;
        this.itemRepo = itemRepo;
        this.issueRepo = issueRepo;
        this.noGen = noGen;
        this.audit = audit;
    }

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "巡检单不存在");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String filterStoreCode) {
        List<Inspection> rows;
        if ("__NONE__".equals(filterStoreCode)) {
            rows = List.of();
        } else {
            Specification<Inspection> spec = DataScope.storeSpec("storeCode");
            if (filterStoreCode != null && !filterStoreCode.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), filterStoreCode.trim()));
            }
            rows = repo.findAll(spec, Sort.by(Sort.Direction.DESC, "inspectedAt"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Inspection r : rows) out.add(toView(r));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        return toView(mustGet(id));
    }

    @Transactional
    public synchronized Map<String, Object> create(String storeCode, CreateCmd cmd, String actor) {
        if (cmd == null) throw badReq("请提供巡检内容");
        if (cmd.type() == null || !TYPES.contains(cmd.type().trim()))
            throw badReq("巡检类型不合法");
        String inspector = trimRequired(cmd.inspector(), "检查人");
        if (cmd.items() == null || cmd.items().isEmpty())
            throw badReq("请至少填写一项检查项");

        OffsetDateTime inspectedAt = parseInspectedAt(cmd.inspectedAt());

        List<InspectionItem> items = new ArrayList<>();
        int sum = 0;
        for (ItemCmd c : cmd.items()) {
            if (c == null || c.name() == null || c.name().isBlank())
                throw badReq("检查项名称不能为空");
            if (c.score() == null) throw badReq("请为所有检查项打分");
            int score = c.score();
            if (score < 0 || score > 10) throw badReq("检查项评分需在 0~10 之间");
            String note = c.note() == null ? null : c.note().trim();
            if (note != null && note.length() > 255) throw badReq("备注内容超出允许长度");
            InspectionItem it = new InspectionItem();
            it.setName(c.name().trim());
            it.setScore(score);
            it.setNote(note == null || note.isEmpty() ? null : note);
            items.add(it);
            sum += score;
        }
        int totalScore = Math.round((float) sum / (items.size() * 10) * 100);

        Inspection ins = new Inspection();
        ins.setInsNo(noGen.nextInsNo());
        ins.setStoreCode(storeCode);
        ins.setInspectedAt(inspectedAt);
        ins.setType(cmd.type().trim());
        ins.setTotalScore(totalScore);
        ins.setInspector(inspector);
        ins.setCreatedAt(OffsetDateTime.now(BIZ_ZONE));

        boolean hasIssue = items.stream().anyMatch((it) -> it.getScore() < 7);
        if (hasIssue) {
            ins.setStatus("PENDING");
        } else {
            ins.setStatus("DONE");
            ins.setCompletedAt(OffsetDateTime.now(BIZ_ZONE));
        }
        repo.save(ins);

        for (InspectionItem it : items) {
            it.setInsId(ins.getId());
            itemRepo.save(it);
        }

        if (hasIssue) {
            for (InspectionItem it : items) {
                if (it.getScore() >= 7) continue;
                RectifyIssue iss = new RectifyIssue();
                iss.setInsId(ins.getId());
                iss.setDescription(buildDesc(it));
                iss.setOwner("待分配");
                iss.setStatus("OPEN");
                iss.setDueAt(inspectedAt.plusDays(7));
                iss.setHasPhoto(false);
                issueRepo.save(iss);
            }
        }
        ins.setIssueCount((int) issueRepo.findByInsIdOrderByIdAsc(ins.getId()).size());
        repo.save(ins);

        audit.record("INSPECTION", ins.getInsNo(), actor, "CREATE",
                "{\"insNo\":" + jsonStr(ins.getInsNo()) + ",\"totalScore\":" + totalScore
                        + ",\"issueCount\":" + ins.getIssueCount() + "}");
        return toView(ins);
    }

    @Transactional
    public Map<String, Object> assignIssue(Long issueId, AssignCmd cmd, String actor) {
        if (cmd == null || cmd.owner() == null || cmd.owner().isBlank())
            throw badReq("请选择整改责任人");
        RectifyIssue iss = issueRepo.findById(issueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "整改问题不存在"));
        if ("DONE".equals(iss.getStatus())) throw badReq("整改已完成，无需指派");
        Inspection parent = mustGet(iss.getInsId());
        if (!DataScope.canReadStore(parent.getStoreCode()))
            throw badReq("无权操作该门店巡检");
        iss.setOwner(cmd.owner().trim());
        if ("OPEN".equals(iss.getStatus())) iss.setStatus("DOING");
        issueRepo.save(iss);

        if ("PENDING".equals(parent.getStatus())) parent.setStatus("IN_PROGRESS");
        repo.save(parent);

        audit.record("INSPECTION", parent.getInsNo(), actor, "ASSIGN",
                "{\"owner\":" + jsonStr(iss.getOwner()) + "}");
        return toView(parent);
    }

    @Transactional
    public Map<String, Object> completeIssue(Long issueId, String actor) {
        RectifyIssue iss = issueRepo.findById(issueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "整改问题不存在"));
        if ("DONE".equals(iss.getStatus())) throw badReq("整改已完成，请勿重复操作");
        Inspection parent = mustGet(iss.getInsId());
        if (!DataScope.canReadStore(parent.getStoreCode()))
            throw badReq("无权操作该门店巡检");
        iss.setStatus("DONE");
        iss.setHasPhoto(true);
        issueRepo.save(iss);

        List<RectifyIssue> all = issueRepo.findByInsIdOrderByIdAsc(parent.getId());
        if (all.stream().allMatch((x) -> "DONE".equals(x.getStatus()))) {
            parent.setStatus("DONE");
            parent.setCompletedAt(OffsetDateTime.now(BIZ_ZONE));
        } else {
            parent.setStatus("IN_PROGRESS");
        }
        repo.save(parent);

        audit.record("INSPECTION", parent.getInsNo(), actor, "COMPLETE",
                "{\"issueId\":" + iss.getId() + "}");
        return toView(parent);
    }

    public record CreateCmd(String type, String inspector, String inspectedAt, List<ItemCmd> items) {}

    public record ItemCmd(String name, Integer score, String note) {}

    public record AssignCmd(String owner) {}

    public record SeedItem(String name, int score, String note) {}

    public record SeedIssue(String desc, String owner, String status,
                            OffsetDateTime dueAt, boolean hasPhoto) {}

    public record SeedInspection(String insNo, OffsetDateTime inspectedAt, String type,
                                 String inspector, String status, List<SeedItem> items,
                                 List<SeedIssue> issues, OffsetDateTime createdAt,
                                 OffsetDateTime completedAt) {}

    @Transactional
    public void seed(String storeCode, SeedInspection c) {
        int sum = 0;
        for (SeedItem si : c.items()) sum += si.score();
        int totalScore = Math.round((float) sum / (c.items().size() * 10) * 100);

        Inspection ins = new Inspection();
        ins.setInsNo(c.insNo());
        ins.setStoreCode(storeCode);
        ins.setInspectedAt(c.inspectedAt());
        ins.setType(c.type());
        ins.setTotalScore(totalScore);
        ins.setIssueCount(c.issues().size());
        ins.setStatus(c.status());
        ins.setInspector(c.inspector());
        ins.setCreatedAt(c.createdAt());
        ins.setCompletedAt(c.completedAt());
        repo.save(ins);

        for (SeedItem si : c.items()) {
            InspectionItem it = new InspectionItem();
            it.setInsId(ins.getId());
            it.setName(si.name());
            it.setScore(si.score());
            it.setNote(si.note());
            itemRepo.save(it);
        }

        for (SeedIssue sq : c.issues()) {
            RectifyIssue iss = new RectifyIssue();
            iss.setInsId(ins.getId());
            iss.setDescription(sq.desc());
            iss.setOwner(sq.owner());
            iss.setStatus(sq.status());
            iss.setDueAt(sq.dueAt());
            iss.setHasPhoto(sq.hasPhoto());
            issueRepo.save(iss);
        }
    }

    private Inspection mustGet(Long id) {
        return repo.findById(id).orElseThrow(InspectionService::notFound);
    }

    private static OffsetDateTime parseInspectedAt(String raw) {
        if (raw == null || raw.isBlank()) throw badReq("请选择检查日期");
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (Exception e) {
            throw badReq("检查日期格式不合法");
        }
    }

    private static String trimRequired(String v, String label) {
        if (v == null || v.isBlank()) throw badReq("请填写" + label);
        String s = v.trim();
        if (s.length() > 32) throw badReq(label + "超出允许长度");
        return s;
    }

    private static String buildDesc(InspectionItem it) {
        String suffix = it.getNote() != null && !it.getNote().isEmpty() ? "：" + it.getNote() : "";
        return it.getName() + " 未达标（" + it.getScore() + "/10）" + suffix;
    }

    private Map<String, Object> toView(Inspection r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("no", r.getInsNo());
        row.put("storeCode", r.getStoreCode());
        row.put("inspectedAt", r.getInspectedAt().toString());
        row.put("type", r.getType());
        row.put("totalScore", r.getTotalScore());
        row.put("issueCount", r.getIssueCount());
        row.put("status", r.getStatus());
        row.put("inspector", r.getInspector());

        List<Map<String, Object>> items = new ArrayList<>();
        for (InspectionItem it : itemRepo.findByInsIdOrderByIdAsc(r.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", it.getName());
            m.put("score", it.getScore());
            m.put("note", it.getNote());
            items.add(m);
        }
        row.put("items", items);

        List<Map<String, Object>> issues = new ArrayList<>();
        for (RectifyIssue iss : issueRepo.findByInsIdOrderByIdAsc(r.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", iss.getId());
            m.put("desc", iss.getDescription());
            m.put("owner", iss.getOwner());
            m.put("status", iss.getStatus());
            m.put("dueAt", iss.getDueAt().toString());
            m.put("hasPhoto", iss.getHasPhoto());
            issues.add(m);
        }
        row.put("issues", issues);

        row.put("createdAt", r.getCreatedAt().toString());
        row.put("completedAt", r.getCompletedAt() == null ? null : r.getCompletedAt().toString());
        return row;
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
