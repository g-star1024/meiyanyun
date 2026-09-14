package com.meiyun.store.health;

import com.meiyun.store.consumable.ConsumableAuditRecorder;
import com.meiyun.store.Store;
import com.meiyun.store.StoreRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class HealthService {

    static final List<String> DIMS = List.of("SAFETY", "SERVICE", "FINANCE", "COMPLIANCE", "STAFF", "EQUIPMENT");
    private static final Set<String> OPEN_STATUSES = Set.of("OPEN", "PROCESSING");
    private static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final HealthCheckRepository checkRepo;
    private final HealthScoreRepository scoreRepo;
    private final HealthIssueRepository issueRepo;
    private final StoreRepository storeRepo;
    private final ConsumableAuditRecorder audit;

    public HealthService(HealthCheckRepository checkRepo, HealthScoreRepository scoreRepo,
                         HealthIssueRepository issueRepo, StoreRepository storeRepo,
                         ConsumableAuditRecorder audit) {
        this.checkRepo = checkRepo;
        this.scoreRepo = scoreRepo;
        this.issueRepo = issueRepo;
        this.storeRepo = storeRepo;
        this.audit = audit;
    }

    public List<Map<String, Object>> listChecks() {
        Map<String, Store> stores = storeRepo.findAll().stream()
                .collect(Collectors.toMap(Store::getStoreCode, Function.identity()));
        Map<String, List<HealthScore>> scoreMap = scoreRepo.findAll().stream()
                .collect(Collectors.groupingBy(HealthScore::getStoreCode));
        List<Map<String, Object>> out = new ArrayList<>();
        for (HealthCheck c : checkRepo.findAllByOrderByStoreCodeAsc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenantId", c.getStoreCode());
            Store s = stores.get(c.getStoreCode());
            row.put("tenantName", s != null ? s.getStoreName() : c.getStoreCode());
            row.put("region", s != null ? s.getRegion() : "");
            row.put("scores", scoresOf(scoreMap.getOrDefault(c.getStoreCode(), List.of())));
            row.put("lastCheckedAt", c.getLastCheckedAt().toString());
            row.put("nextCheckAt", c.getNextCheckAt().toString());
            row.put("inspector", c.getInspector());
            out.add(row);
        }
        return out;
    }

    public List<Map<String, Object>> listIssues(String storeCode, String status) {
        List<HealthIssue> issues;
        boolean hasStore = storeCode != null && !storeCode.isBlank();
        boolean hasStatus = status != null && !status.isBlank();
        if (hasStore && hasStatus) issues = issueRepo.findByStoreCodeAndStatusOrderByIdAsc(storeCode, status);
        else if (hasStore) issues = issueRepo.findByStoreCodeOrderByIdAsc(storeCode);
        else if (hasStatus) issues = issueRepo.findByStatusOrderByIdAsc(status);
        else issues = issueRepo.findAllByOrderByIdAsc();
        Map<String, Store> stores = storeRepo.findAll().stream()
                .collect(Collectors.toMap(Store::getStoreCode, Function.identity()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (HealthIssue i : issues) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", issueRef(i.getId()));
            row.put("tenantId", i.getStoreCode());
            Store s = stores.get(i.getStoreCode());
            row.put("tenantName", s != null ? s.getStoreName() : i.getStoreCode());
            row.put("dimension", i.getDimension());
            row.put("severity", i.getSeverity());
            row.put("title", i.getTitle());
            row.put("detail", i.getDetail());
            row.put("status", i.getStatus());
            row.put("assignee", i.getAssignee());
            row.put("dueAt", i.getDueAt() != null ? i.getDueAt().toString() : null);
            row.put("createdAt", i.getCreatedAt() != null ? i.getCreatedAt().toLocalDate().toString() : null);
            row.put("resolvedAt", i.getResolvedAt() != null ? i.getResolvedAt().toString() : null);
            row.put("resolution", i.getResolution());
            out.add(row);
        }
        return out;
    }

    @Transactional
    public void startIssue(String ref, String actor) {
        HealthIssue i = loadIssue(ref);
        if (!"OPEN".equals(i.getStatus())) throw unprocessable("仅未处理任务可开始处理");
        i.setStatus("PROCESSING");
        issueRepo.save(i);
        audit.record("HEALTH_ISSUE", ref, actor, "健康整改开始处理",
                "{\"tenantId\":\"" + i.getStoreCode() + "\",\"title\":\"" + jsonEscape(i.getTitle()) + "\"}");
    }

    @Transactional
    public void resolveIssue(String ref, String resolution, String actor) {
        if (resolution == null || resolution.isBlank()) throw badReq("resolution 必填");
        HealthIssue i = loadIssue(ref);
        if (!"PROCESSING".equals(i.getStatus())) throw unprocessable("仅处理中任务可解决");
        i.setStatus("RESOLVED");
        i.setResolvedAt(today());
        i.setResolution(resolution.trim());
        issueRepo.save(i);
        audit.record("HEALTH_ISSUE", ref, actor, "健康整改解决",
                "{\"tenantId\":\"" + i.getStoreCode() + "\",\"resolution\":\"" + jsonEscape(resolution.trim()) + "\"}");
    }

    @Transactional
    public void ignoreIssue(String ref, String actor) {
        HealthIssue i = loadIssue(ref);
        if (!OPEN_STATUSES.contains(i.getStatus())) throw unprocessable("仅未处理/处理中任务可忽略");
        i.setStatus("IGNORED");
        issueRepo.save(i);
        audit.record("HEALTH_ISSUE", ref, actor, "健康整改忽略",
                "{\"tenantId\":\"" + i.getStoreCode() + "\",\"title\":\"" + jsonEscape(i.getTitle()) + "\"}");
    }

    @Transactional
    public Map<String, Object> rerun(String storeCode, String operator) {
        HealthCheck c = checkRepo.findById(storeCode).orElseThrow(() -> notFound("门店巡检档案不存在"));
        long openHigh = countOpen(storeCode, "HIGH");
        long openMid = countOpen(storeCode, "MEDIUM");
        List<HealthScore> scores = scoreRepo.findByStoreCode(storeCode);
        int delta = (int) (openHigh * 8 + openMid * 3);
        int bonus = (openHigh + openMid == 0) ? 4 : 0;
        int sum = 0;
        int weightSum = 0;
        for (HealthScore s : scores) {
            int v = Math.max(40, Math.min(98, s.getScore() - delta + bonus));
            s.setScore(v);
            scoreRepo.save(s);
            sum += v * s.getWeight();
            weightSum += s.getWeight();
        }
        c.setLastCheckedAt(today());
        c.setNextCheckAt(today().plusDays(7));
        c.setInspector(operator);
        checkRepo.save(c);
        int overall = weightSum == 0 ? 0 : Math.round((float) sum / weightSum);
        audit.record("HEALTH_CHECK", storeCode, operator, "健康重新巡检",
                "{\"openHigh\":" + openHigh + ",\"openMid\":" + openMid + ",\"overall\":" + overall + "}");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenantId", storeCode);
        Store s = storeRepo.findById(storeCode).orElse(null);
        row.put("tenantName", s != null ? s.getStoreName() : storeCode);
        row.put("region", s != null ? s.getRegion() : "");
        row.put("scores", scoresOf(scores));
        row.put("lastCheckedAt", c.getLastCheckedAt().toString());
        row.put("nextCheckAt", c.getNextCheckAt().toString());
        row.put("inspector", c.getInspector());
        return row;
    }

    private long countOpen(String storeCode, String severity) {
        return issueRepo.countByStoreCodeAndSeverityAndStatusIn(storeCode, severity, OPEN_STATUSES);
    }

    private List<Integer> scoresOf(List<HealthScore> list) {
        Map<String, Integer> map = list.stream()
                .collect(Collectors.toMap(HealthScore::getDimension, HealthScore::getScore));
        List<Integer> out = new ArrayList<>();
        for (String d : DIMS) out.add(map.getOrDefault(d, 0));
        return out;
    }

    private HealthIssue loadIssue(String ref) {
        return issueRepo.findById(parseRef(ref)).orElseThrow(() -> notFound("整改任务不存在"));
    }

    static String issueRef(Long id) {
        return String.format("I%02d", id);
    }

    static long parseRef(String ref) {
        if (ref == null) throw badReq("任务编号非法");
        String digits = ref.startsWith("I") ? ref.substring(1) : ref;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw badReq("任务编号非法");
        }
    }

    static boolean validDimension(String d) {
        return DIMS.contains(d);
    }

    static boolean validSeverity(String s) {
        return SEVERITIES.contains(s);
    }

    static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }

    static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
