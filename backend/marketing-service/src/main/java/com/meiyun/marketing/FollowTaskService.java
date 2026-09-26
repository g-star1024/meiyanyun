package com.meiyun.marketing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 跟进任务服务（M3-B2 / DESIGN-M3 §3 D1）。
 *
 * <p>列表+KPI 同响应；KPI 与前端 mock computed 全量口径一致（我的待跟进/今日到期/已逾期/本月完成），
 * 不受 status 过滤影响。OVERDUE 查询侧推导（deadline&lt;now 且 PENDING），不落库刷写。
 *
 * <p>AI 下发幂等（D8）：idemKey 先查命中直返；未命中 insert 撞部分唯一索引
 * 捕 DataIntegrityViolationException 重查直返（照 GrantService.saveIdempotent 先例），不重复建单。
 *
 * <p>无类级/方法级 @Transactional（沿 AutoGrantService 范式：内层事务异常被外层捕获
 * 触发 rollback-only 污染；单实体落库由 repository 事务兜底）。
 */
@Service
public class FollowTaskService {

    private static final ZoneOffset BIZ_TZ = ZoneOffset.of("+08:00");
    private static final List<String> TYPES = List.of("PHONE", "WECHAT", "IN_STORE", "BIRTHDAY", "POST_OP", "CONTENT");
    private static final List<String> PRIORITIES = List.of("HIGH", "MEDIUM", "LOW");
    private static final List<String> AI_SOURCES = List.of("CHURN", "REPURCHASE");

    private final FollowTaskRepository followRepo;
    private final BizNoGenerator bizNo;
    private final AuditRecorder audit;
    private final ObjectMapper om;

    public FollowTaskService(FollowTaskRepository followRepo, BizNoGenerator bizNo,
                             AuditRecorder audit, ObjectMapper om) {
        this.followRepo = followRepo;
        this.bizNo = bizNo;
        this.audit = audit;
        this.om = om;
    }

    // ==================== 视图契约 ====================

    /** 单条日志（对齐前端 FollowTaskLog：最新在前）。 */
    public record LogEntry(String by, String text, String at) {
    }

    /** 任务视图（id=follow_no，与前端视图契约一致；OVERDUE 已推导）。 */
    public record FollowTaskView(String id, String customerId, String customerName, String customerLevel,
                                 String type, String content, String deadline, String status, String priority,
                                 String assignee, String createdAt, String completedAt,
                                 List<LogEntry> logs, String source, String sourceId) {
    }

    /** KPI 四键（对齐前端 mock computed 口径）。 */
    public record KpiView(long pending, long dueToday, long overdue, long doneThisMonth) {
    }

    public record ListResp(List<FollowTaskView> tasks, KpiView kpi) {
    }

    /** 人工创建入参。 */
    public record CreateCmd(String customerId, String customerName, String customerLevel, String type,
                            String content, String deadline, String priority, String assignee, String storeCode) {
    }

    public record ReassignCmd(String assignee) {
    }

    public record LogCmd(String text) {
    }

    /** AI 下发入参（内部端点；idemKey 必填）。 */
    public record InternalCreateCmd(String customerId, String customerName, String customerLevel, String type,
                                    String content, String deadline, String priority, String assignee,
                                    String source, String sourceId, String idemKey, String storeCode) {
    }

    // ==================== 查询 ====================

    /** 列表+KPI 同响应。status=PENDING/DONE/OVERDUE/ALL；storeCode 精确（NULL 行=全连锁，随任一门店可见）。 */
    public ListResp list(String status, String storeCode) {
        String st = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase();
        OffsetDateTime now = OffsetDateTime.now();
        List<FollowTask> scoped = followRepo.findAllByOrderByCreatedAtDesc().stream()
                .filter(t -> storeCode == null || storeCode.isBlank()
                        || t.getStoreCode() == null || storeCode.equals(t.getStoreCode()))
                .toList();
        List<FollowTaskView> tasks = scoped.stream()
                .map(t -> toView(t, now))
                .filter(v -> "ALL".equals(st) || st.equals(v.status()))
                .toList();
        return new ListResp(tasks, kpi(scoped, now));
    }

    /** KPI 全量口径（不受 status 过滤影响）：我的待跟进/今日到期/已逾期/本月完成。 */
    private KpiView kpi(List<FollowTask> scoped, OffsetDateTime now) {
        String me = currentStaffName();
        LocalDate today = now.atZoneSameInstant(BIZ_TZ).toLocalDate();
        long pending = 0, dueToday = 0, overdue = 0, doneThisMonth = 0;
        for (FollowTask t : scoped) {
            String eff = effectiveStatus(t, now);
            if ("PENDING".equals(eff) && me.equals(t.getAssignee())) pending++;
            if (!"DONE".equals(eff) && t.getDeadline() != null
                    && today.equals(t.getDeadline().atZoneSameInstant(BIZ_TZ).toLocalDate())) dueToday++;
            if ("OVERDUE".equals(eff)) overdue++;
            if ("DONE".equals(t.getStatus()) && t.getCompletedAt() != null) {
                LocalDate doneDay = t.getCompletedAt().atZoneSameInstant(BIZ_TZ).toLocalDate();
                if (doneDay.getYear() == today.getYear() && doneDay.getMonth() == today.getMonth()) doneThisMonth++;
            }
        }
        return new KpiView(pending, dueToday, overdue, doneThisMonth);
    }

    // ==================== 人工动作 ====================

    /** 人工创建（MANUAL）。默认 customerLevel=普通/priority=MEDIUM/assignee=当前用户。 */
    public FollowTaskView create(CreateCmd cmd) {
        if (cmd == null || cmd.customerName() == null || cmd.customerName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户姓名不能为空");
        }
        if (cmd.type() == null || !TYPES.contains(cmd.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务类型不合法（PHONE/WECHAT/IN_STORE/BIRTHDAY/POST_OP/CONTENT）");
        }
        if (cmd.content() == null || cmd.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务内容不能为空");
        }
        OffsetDateTime deadline = parseDeadline(cmd.deadline());
        String priority = cmd.priority() == null || cmd.priority().isBlank() ? "MEDIUM" : cmd.priority().trim().toUpperCase();
        if (!PRIORITIES.contains(priority)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "优先级不合法（HIGH/MEDIUM/LOW）");
        }
        String me = currentStaffName();

        FollowTask t = new FollowTask();
        t.setFollowNo(nextFollowNo());
        t.setCustomerId(blankToNull(cmd.customerId()));
        t.setCustomerName(cmd.customerName().trim());
        t.setCustomerLevel(cmd.customerLevel() == null || cmd.customerLevel().isBlank() ? "普通" : cmd.customerLevel().trim());
        t.setType(cmd.type());
        t.setContent(cmd.content().trim());
        t.setDeadline(deadline);
        t.setStatus("PENDING");
        t.setPriority(priority);
        t.setAssignee(cmd.assignee() == null || cmd.assignee().isBlank() ? me : cmd.assignee().trim());
        t.setSource("MANUAL");
        t.setStoreCode(blankToNull(cmd.storeCode()));
        t.setCreatedBy(DataScope.currentActor());
        OffsetDateTime now = OffsetDateTime.now();
        t.setLogs(writeLogs(List.of(new LogEntry(me, "创建跟进任务", iso(now)))));
        followRepo.save(t);
        audit("CREATE", t.getFollowNo(), Map.of("customerName", t.getCustomerName(), "type", t.getType(),
                "priority", t.getPriority(), "assignee", t.getAssignee()));
        return toView(t, now);
    }

    /** 标记完成（DONE 禁重复操作）。 */
    public FollowTaskView complete(String followNo) {
        FollowTask t = mustGet(followNo);
        if ("DONE".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务已完成，请勿重复操作");
        }
        String me = currentStaffName();
        OffsetDateTime now = OffsetDateTime.now();
        t.setStatus("DONE");
        t.setCompletedAt(now);
        List<LogEntry> logs = readLogs(t.getLogs());
        logs.add(0, new LogEntry(me, "标记完成", iso(now)));
        t.setLogs(writeLogs(logs));
        followRepo.save(t);
        audit("COMPLETE", followNo, Map.of("customerName", t.getCustomerName(), "assignee", me));
        return toView(t, now);
    }

    /** 改派（DONE 禁操作）。 */
    public FollowTaskView reassign(String followNo, ReassignCmd cmd) {
        FollowTask t = mustGet(followNo);
        if ("DONE".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务已完成，不能改派");
        }
        if (cmd == null || cmd.assignee() == null || cmd.assignee().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "改派人不能为空");
        }
        String me = currentStaffName();
        String to = cmd.assignee().trim();
        OffsetDateTime now = OffsetDateTime.now();
        String from = t.getAssignee();
        t.setAssignee(to);
        List<LogEntry> logs = readLogs(t.getLogs());
        logs.add(0, new LogEntry(me, "改派给 " + to, iso(now)));
        t.setLogs(writeLogs(logs));
        followRepo.save(t);
        audit("REASSIGN", followNo, Map.of("from", from == null ? "" : from, "to", to));
        return toView(t, now);
    }

    /** 追加跟进记录（最新在前；DONE 禁补记，契约定稿口径与 complete/reassign 一致）。 */
    public FollowTaskView addLog(String followNo, LogCmd cmd) {
        FollowTask t = mustGet(followNo);
        if ("DONE".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务已完成，禁止补记");
        }
        if (cmd == null || cmd.text() == null || cmd.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "跟进记录不能为空");
        }
        String me = currentStaffName();
        OffsetDateTime now = OffsetDateTime.now();
        List<LogEntry> logs = readLogs(t.getLogs());
        logs.add(0, new LogEntry(me, cmd.text().trim(), iso(now)));
        t.setLogs(writeLogs(logs));
        followRepo.save(t);
        audit("ADD_LOG", followNo, Map.of("by", me));
        return toView(t, now);
    }

    // ==================== AI 下发（内部幂等） ====================

    /**
     * AI 干预下发建任务（CHURN/REPURCHASE；D3-1/2）。幂等：idemKey 先查命中直返；
     * 撞唯一约束捕异常重查直返（D8），重放不重复建单、不重复审计。
     */
    public FollowTaskView internalCreate(InternalCreateCmd cmd) {
        if (cmd == null || cmd.idemKey() == null || cmd.idemKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "幂等键不能为空");
        }
        if (cmd.source() == null || !AI_SOURCES.contains(cmd.source())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源不合法（CHURN/REPURCHASE）");
        }
        if (cmd.customerName() == null || cmd.customerName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户姓名不能为空");
        }
        if (cmd.type() == null || !TYPES.contains(cmd.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务类型不合法（PHONE/WECHAT/IN_STORE/BIRTHDAY/POST_OP/CONTENT）");
        }
        var dup = followRepo.findByIdemKey(cmd.idemKey().trim());
        if (dup.isPresent()) {
            return toView(dup.get(), OffsetDateTime.now());
        }
        String priority = cmd.priority() == null || cmd.priority().isBlank() ? "HIGH" : cmd.priority().trim().toUpperCase();
        if (!PRIORITIES.contains(priority)) {
            priority = "HIGH";
        }
        OffsetDateTime now = OffsetDateTime.now();

        FollowTask t = new FollowTask();
        t.setFollowNo(nextFollowNo());
        t.setCustomerId(blankToNull(cmd.customerId()));
        t.setCustomerName(cmd.customerName().trim());
        t.setCustomerLevel(blankToNull(cmd.customerLevel()));
        t.setType(cmd.type());
        t.setContent(cmd.content() == null || cmd.content().isBlank() ? "AI 预警干预跟进" : cmd.content().trim());
        t.setDeadline(cmd.deadline() == null || cmd.deadline().isBlank()
                ? now.plusDays(1) : parseDeadline(cmd.deadline()));
        t.setStatus("PENDING");
        t.setPriority(priority);
        t.setAssignee(cmd.assignee() == null || cmd.assignee().isBlank() ? "系统派发" : cmd.assignee().trim());
        t.setSource(cmd.source());
        t.setSourceId(blankToNull(cmd.sourceId()));
        t.setIdemKey(cmd.idemKey().trim());
        t.setStoreCode(blankToNull(cmd.storeCode()));
        t.setCreatedBy("system");
        t.setLogs(writeLogs(List.of(new LogEntry("系统",
                "AI 派发（" + cmd.source() + (cmd.sourceId() == null ? "" : "/" + cmd.sourceId()) + "）", iso(now)))));
        FollowTask saved = saveIdempotent(t);
        if (saved == t) {
            audit("INTERNAL_CREATE", saved.getFollowNo(), Map.of("source", saved.getSource(),
                    "sourceId", saved.getSourceId() == null ? "" : saved.getSourceId(),
                    "idemKey", saved.getIdemKey(), "customerName", saved.getCustomerName()));
        }
        return toView(saved, now);
    }

    /** insert 撞 idem_key 部分唯一索引（并发同键）→ 重查直返既有任务（幂等语义，照 GrantService 先例）。 */
    private FollowTask saveIdempotent(FollowTask t) {
        try {
            return followRepo.saveAndFlush(t);
        } catch (DataIntegrityViolationException dup) {
            return followRepo.findByIdemKey(t.getIdemKey())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "跟进任务唯一键冲突且无法重查，请稍后重试"));
        }
    }

    // ==================== 内部工具 ====================

    private FollowTask mustGet(String followNo) {
        return followRepo.findByFollowNo(followNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "跟进任务不存在"));
    }

    private String nextFollowNo() {
        return bizNo.next("FT", like -> followRepo.findTopByFollowNoLikeOrderByFollowNoDesc(like)
                .map(FollowTask::getFollowNo).orElse(null));
    }

    /** OVERDUE 查询侧推导：deadline&lt;now 且 PENDING。 */
    private String effectiveStatus(FollowTask t, OffsetDateTime now) {
        if ("PENDING".equals(t.getStatus()) && t.getDeadline() != null && t.getDeadline().isBefore(now)) {
            return "OVERDUE";
        }
        return t.getStatus();
    }

    private FollowTaskView toView(FollowTask t, OffsetDateTime now) {
        return new FollowTaskView(t.getFollowNo(), t.getCustomerId(), t.getCustomerName(), t.getCustomerLevel(),
                t.getType(), t.getContent(), iso(t.getDeadline()), effectiveStatus(t, now), t.getPriority(),
                t.getAssignee(), iso(t.getCreatedAt()), iso(t.getCompletedAt()),
                readLogs(t.getLogs()), t.getSource(), t.getSourceId());
    }

    private List<LogEntry> readLogs(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(om.readValue(json, new TypeReference<List<LogEntry>>() {
            }));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeLogs(List<LogEntry> logs) {
        try {
            return om.writeValueAsString(logs);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "跟进日志序列化失败");
        }
    }

    private static OffsetDateTime parseDeadline(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "截止时间不能为空");
        }
        String s = raw.trim();
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(s).atOffset(BIZ_TZ);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "截止时间格式不合法（ISO-8601）");
            }
        }
    }

    private static String currentStaffName() {
        LoginUser u = DataScope.current();
        return (u == null || u.staffName() == null || u.staffName().isBlank()) ? "system" : u.staffName();
    }

    private static String iso(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void audit(String action, String followNo, Map<String, String> payload) {
        try {
            audit.record("FOLLOW_TASK", followNo, DataScope.currentActor(), action, om.writeValueAsString(payload));
        } catch (Exception e) {
            audit.record("FOLLOW_TASK", followNo, DataScope.currentActor(), action, "{}");
        }
    }
}
