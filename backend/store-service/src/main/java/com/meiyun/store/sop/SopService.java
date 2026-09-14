package com.meiyun.store.sop;

import com.meiyun.security.DataScope;
import com.meiyun.store.Store;
import com.meiyun.store.StoreRepository;
import com.meiyun.store.consumable.ConsumableAuditRecorder;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SOP 标准作业流程域服务（B49 卡6）：模板三态（DRAFT → PUBLISHED，发布版本末位 +1）
 * + 执行任务状态机（PENDING → IN_PROGRESS → DONE）。
 *
 * <p>OVERDUE 不落库：DTO 组装时派生（{@code status != DONE && dueDate < today(Asia/Shanghai)}），
 * 列表 status 过滤参数按派生态匹配；状态机判断一律以落库值为准（落库 PENDING 即使派生 OVERDUE 仍可 start）。
 *
 * <p>DTO id 适配层保前端 view 零改动：模板输出 {@code S%02d}、任务输出 {@code TK%02d}、
 * 步骤输出 {@code 's' + stepNo}（镜像 mock 字面量）；入参反向 strip 前缀解析。
 * 门店名本地 {@link StoreRepository} 解析，零跨服务调用。
 */
@Service
public class SopService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern TRAILING_DIGITS = Pattern.compile("^(.*?)(\\d+)$");

    private final SopTemplateRepository templateRepo;
    private final SopTemplateStepRepository stepRepo;
    private final SopTaskRepository taskRepo;
    private final StoreRepository storeRepo;
    private final ConsumableAuditRecorder audit;

    public SopService(SopTemplateRepository templateRepo,
                      SopTemplateStepRepository stepRepo,
                      SopTaskRepository taskRepo,
                      StoreRepository storeRepo,
                      ConsumableAuditRecorder audit) {
        this.templateRepo = templateRepo;
        this.stepRepo = stepRepo;
        this.taskRepo = taskRepo;
        this.storeRepo = storeRepo;
        this.audit = audit;
    }

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }

    static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    // ============================================================
    // 模板
    // ============================================================

    /** 模板列表（集团级流程库，不分门店），含步骤。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTemplates(String status) {
        String st = isBlank(status) ? null : status.trim();
        List<Map<String, Object>> out = new ArrayList<>();
        for (SopTemplate t : templateRepo.search(st)) {
            out.add(templateRow(t));
        }
        return out;
    }

    /** 新建模板：固定 v1.0 DRAFT；code 按「SOP-<类目首字母>-%03d」前缀递号。 */
    @Transactional
    public Map<String, Object> createTemplate(String title, String category, String owner,
                                              List<String> applicableStores, List<StepCmd> steps,
                                              String operator) {
        if (isBlank(title) || isBlank(category) || isBlank(owner)) {
            throw badReq("模板名称、类目、责任部门均不能为空");
        }
        if (steps == null || steps.isEmpty()) {
            throw badReq("SOP 至少需要一个执行步骤");
        }
        SopTemplate t = new SopTemplate();
        t.setCode(nextCode(category.trim()));
        t.setTitle(title.trim());
        t.setCategory(category.trim());
        t.setVersion("v1.0");
        t.setStatus("DRAFT");
        t.setOwner(owner.trim());
        t.setApplicableStores(joinStores(applicableStores));
        templateRepo.save(t);

        int no = 0;
        for (StepCmd s : steps) {
            if (s == null || isBlank(s.title())) {
                throw badReq("步骤标题不能为空");
            }
            SopTemplateStep step = new SopTemplateStep();
            step.setTemplateId(t.getId());
            step.setStepNo(++no);
            step.setTitle(s.title().trim());
            step.setDescription(isBlank(s.desc()) ? null : s.desc().trim());
            step.setRequirePhoto(s.requirePhoto() != null && s.requirePhoto());
            stepRepo.save(step);
        }
        audit.record("SOP_TEMPLATE", t.getCode(), isBlank(operator) ? "system" : operator, "SOP模板创建",
                String.format("{\"code\":%s,\"title\":%s,\"category\":%s,\"steps\":%d}",
                        jsonStr(t.getCode()), jsonStr(t.getTitle()), jsonStr(t.getCategory()), no));
        return templateRow(t);
    }

    /** 发布模板：仅 DRAFT 可发布（否则 409）；版本末位数字 +1（v1.2 → v1.3，镜像 mock）。 */
    @Transactional
    public Map<String, Object> publishTemplate(String idRef, String operator) {
        SopTemplate t = mustGetTemplate(idRef);
        if (!"DRAFT".equals(t.getStatus())) {
            throw conflict("仅草稿模板可发布，当前状态「" + t.getStatus() + "」");
        }
        t.setStatus("PUBLISHED");
        t.setVersion(bumpVersion(t.getVersion()));
        t.setUpdatedAt(OffsetDateTime.now());
        templateRepo.save(t);
        audit.record("SOP_TEMPLATE", t.getCode(), isBlank(operator) ? "system" : operator, "SOP模板发布",
                String.format("{\"code\":%s,\"version\":%s}", jsonStr(t.getCode()), jsonStr(t.getVersion())));
        return templateRow(t);
    }

    // ============================================================
    // 执行任务
    // ============================================================

    /** 任务列表：数据域流式过滤（规避 null 码全量越权）；status 参数按派生态匹配。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTasks(String storeCode, String status) {
        String sc = isBlank(storeCode) ? null : storeCode.trim();
        String st = isBlank(status) ? null : status.trim();
        if ("__NONE__".equals(sc)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SopTask t : taskRepo.search(sc, null)) {
            if (!DataScope.canReadStore(t.getStoreCode())) {
                continue;
            }
            String derived = derivedStatus(t);
            if (st != null && !st.equals(derived)) {
                continue;
            }
            out.add(taskRow(t, derived));
        }
        return out;
    }

    /** 任务门店码（Controller 写断言用）；任务不存在 404。 */
    @Transactional(readOnly = true)
    public String taskStoreCode(String idRef) {
        return mustGetTask(idRef).getStoreCode();
    }

    /** 派单：模板须 PUBLISHED（422）且适用门店含目标店（422）；快照模板三要素。 */
    @Transactional
    public Map<String, Object> createTask(String templateRef, String storeCode, String assignee,
                                          String priority, String dueDate, String operator) {
        SopTemplate t = mustGetTemplate(templateRef);
        if (!"PUBLISHED".equals(t.getStatus())) {
            throw unprocessable("仅已发布模板可派单，当前状态「" + t.getStatus() + "」");
        }
        if (isBlank(storeCode)) throw badReq("请指定执行门店");
        String sc = storeCode.trim();
        storeRepo.findById(sc).orElseThrow(() -> badReq("执行门店「" + sc + "」不存在"));
        if (!"ALL".equals(t.getApplicableStores())
                && !List.of(t.getApplicableStores().split(",")).contains(sc)) {
            throw unprocessable("模板「" + t.getTitle() + "」不适用门店「" + sc + "」");
        }
        if (isBlank(assignee)) throw badReq("请指定执行人");
        LocalDate due = parseDate(dueDate);
        if (due == null) throw badReq("请指定截止日期（yyyy-MM-dd）");

        SopTask task = new SopTask();
        task.setTemplateId(t.getId());
        task.setTemplateCode(t.getCode());
        task.setTemplateTitle(t.getTitle());
        task.setCategory(t.getCategory());
        task.setStoreCode(sc);
        task.setAssignee(assignee.trim());
        task.setPriority(normalizePriority(priority));
        task.setDueDate(due);
        task.setStatus("PENDING");
        task.setCompletedStepIds("");
        task.setCreatedBy(isBlank(operator) ? "system" : operator);
        taskRepo.save(task);
        audit.record("SOP_TASK", taskRef(task.getId()), task.getCreatedBy(), "SOP任务派单",
                String.format("{\"templateCode\":%s,\"storeCode\":%s,\"assignee\":%s,\"dueDate\":%s}",
                        jsonStr(t.getCode()), jsonStr(sc), jsonStr(task.getAssignee()), jsonStr(due.toString())));
        return taskRow(task, derivedStatus(task));
    }

    /** 开始执行：落库 PENDING → IN_PROGRESS（派生 OVERDUE 不阻断），startedAt = 今日。 */
    @Transactional
    public Map<String, Object> startTask(String idRef, String operator) {
        SopTask task = mustGetTask(idRef);
        if (!"PENDING".equals(task.getStatus())) {
            throw unprocessable("仅待执行任务可开始，当前状态「" + derivedStatus(task) + "」");
        }
        task.setStatus("IN_PROGRESS");
        task.setStartedAt(today());
        taskRepo.save(task);
        audit.record("SOP_TASK", taskRef(task.getId()), isBlank(operator) ? "system" : operator, "SOP任务开始执行",
                String.format("{\"templateCode\":%s,\"storeCode\":%s}",
                        jsonStr(task.getTemplateCode()), jsonStr(task.getStoreCode())));
        return taskRow(task, derivedStatus(task));
    }

    /** 勾选/取消步骤：DONE 禁改（409）；步骤须属本任务模板；不改变任务状态；不审计。 */
    @Transactional
    public Map<String, Object> toggleStep(String idRef, String stepRef, String operator) {
        SopTask task = mustGetTask(idRef);
        if ("DONE".equals(task.getStatus())) {
            throw conflict("任务已完成，步骤不可再修改");
        }
        int stepNo = parseStepNo(stepRef);
        List<SopTemplateStep> steps = stepRepo.findByTemplateIdOrderByStepNoAsc(task.getTemplateId());
        boolean exists = steps.stream().anyMatch(s -> s.getStepNo() == stepNo);
        if (!exists) {
            throw badReq("步骤「" + stepRef + "」不属于该任务模板");
        }
        String ref = "s" + stepNo;
        List<String> done = new ArrayList<>(splitRefs(task.getCompletedStepIds()));
        if (!done.remove(ref)) {
            done.add(ref);
        }
        task.setCompletedStepIds(String.join(",", done));
        taskRepo.save(task);
        return taskRow(task, derivedStatus(task));
    }

    /** 完成任务：note 必填（400）；DONE 禁重复（409）；全步骤勾选（422）；补齐全步骤快照。 */
    @Transactional
    public Map<String, Object> completeTask(String idRef, String note, String operator) {
        if (isBlank(note)) {
            throw badReq("完成备注不能为空");
        }
        SopTask task = mustGetTask(idRef);
        if ("DONE".equals(task.getStatus())) {
            throw conflict("任务已完成，不可重复操作");
        }
        List<SopTemplateStep> steps = stepRepo.findByTemplateIdOrderByStepNoAsc(task.getTemplateId());
        List<String> done = splitRefs(task.getCompletedStepIds());
        if (done.size() < steps.size()) {
            throw unprocessable("尚有步骤未执行（" + done.size() + "/" + steps.size() + "），不能完成");
        }
        List<String> all = new ArrayList<>();
        for (SopTemplateStep s : steps) {
            all.add("s" + s.getStepNo());
        }
        task.setCompletedStepIds(String.join(",", all));
        task.setStatus("DONE");
        task.setCompletedAt(today());
        task.setNote(note.trim());
        taskRepo.save(task);
        audit.record("SOP_TASK", taskRef(task.getId()), isBlank(operator) ? "system" : operator, "SOP任务完成",
                String.format("{\"templateCode\":%s,\"storeCode\":%s,\"note\":%s}",
                        jsonStr(task.getTemplateCode()), jsonStr(task.getStoreCode()), jsonStr(task.getNote())));
        return taskRow(task, derivedStatus(task));
    }

    // ============================================================
    // DTO 组装（id 适配层：模板 S%02d / 任务 TK%02d / 步骤 's'+stepNo）
    // ============================================================

    private Map<String, Object> templateRow(SopTemplate t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", templateRef(t.getId()));
        row.put("code", t.getCode());
        row.put("title", t.getTitle());
        row.put("category", t.getCategory());
        row.put("version", t.getVersion());
        row.put("status", t.getStatus());
        row.put("owner", t.getOwner());
        row.put("updatedAt", t.getUpdatedAt() == null ? null : t.getUpdatedAt().atZoneSameInstant(ZONE).toLocalDate().toString());
        row.put("applicableStores", "ALL".equals(t.getApplicableStores())
                ? List.of("ALL") : List.of(t.getApplicableStores().split(",")));
        List<Map<String, Object>> stepRows = new ArrayList<>();
        for (SopTemplateStep s : stepRepo.findByTemplateIdOrderByStepNoAsc(t.getId())) {
            Map<String, Object> sr = new LinkedHashMap<>();
            sr.put("id", "s" + s.getStepNo());
            sr.put("title", s.getTitle());
            sr.put("desc", s.getDescription());
            sr.put("requirePhoto", s.getRequirePhoto());
            stepRows.add(sr);
        }
        row.put("steps", stepRows);
        return row;
    }

    private Map<String, Object> taskRow(SopTask t, String derived) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", taskRef(t.getId()));
        row.put("templateId", templateRef(t.getTemplateId()));
        row.put("templateTitle", t.getTemplateTitle());
        row.put("category", t.getCategory());
        row.put("tenantId", t.getStoreCode());
        Store st = storeRepo.findById(t.getStoreCode()).orElse(null);
        row.put("tenantName", st == null ? t.getStoreCode() : st.getStoreName());
        row.put("assignee", t.getAssignee());
        row.put("priority", t.getPriority());
        row.put("dueAt", t.getDueDate().toString());
        row.put("status", derived);
        row.put("completedSteps", splitRefs(t.getCompletedStepIds()));
        row.put("note", t.getNote());
        row.put("startedAt", t.getStartedAt() == null ? null : t.getStartedAt().toString());
        row.put("completedAt", t.getCompletedAt() == null ? null : t.getCompletedAt().toString());
        return row;
    }

    // ============================================================
    // 辅助
    // ============================================================

    /** 派生态：未完成且截止日早于今日 → OVERDUE；否则落库值。 */
    static String derivedStatus(SopTask t) {
        if (!"DONE".equals(t.getStatus()) && t.getDueDate().isBefore(today())) {
            return "OVERDUE";
        }
        return t.getStatus();
    }

    static String templateRef(Long id) {
        return String.format("S%02d", id);
    }

    static String taskRef(Long id) {
        return String.format("TK%02d", id);
    }

    private SopTemplate mustGetTemplate(String idRef) {
        return templateRepo.findById(parseRef(idRef, "S"))
                .orElseThrow(() -> notFound("SOP 模板不存在"));
    }

    private SopTask mustGetTask(String idRef) {
        return taskRepo.findById(parseRef(idRef, "TK"))
                .orElseThrow(() -> notFound("SOP 任务不存在"));
    }

    /** 入参 id 反向解析：接受「S01」/「TK01」/纯数字。 */
    private static Long parseRef(String ref, String prefix) {
        if (isBlank(ref)) throw badReq("ID 不能为空");
        String r = ref.trim();
        if (r.toUpperCase().startsWith(prefix)) {
            r = r.substring(prefix.length());
        }
        try {
            return Long.parseLong(r);
        } catch (NumberFormatException e) {
            throw badReq("ID「" + ref + "」格式非法");
        }
    }

    private static int parseStepNo(String stepRef) {
        if (isBlank(stepRef)) throw badReq("步骤 ID 不能为空");
        String r = stepRef.trim();
        if (r.toLowerCase().startsWith("s")) {
            r = r.substring(1);
        }
        try {
            return Integer.parseInt(r);
        } catch (NumberFormatException e) {
            throw badReq("步骤 ID「" + stepRef + "」格式非法");
        }
    }

    /** code 递号：前缀「SOP-<类目首字母>-」查 max 序号 +1（%03d）。 */
    private String nextCode(String category) {
        String prefix = "SOP-" + Character.toUpperCase(category.charAt(0)) + "-";
        int max = 0;
        for (SopTemplate t : templateRepo.findByCodeStartingWith(prefix)) {
            String tail = t.getCode().substring(t.getCode().lastIndexOf('-') + 1);
            try {
                max = Math.max(max, Integer.parseInt(tail));
            } catch (NumberFormatException ignored) {
            }
        }
        return prefix + String.format("%03d", max + 1);
    }

    /** 版本末位数字 +1（v1.2 → v1.3），无末位数字则原样保留（镜像 mock replace 语义）。 */
    static String bumpVersion(String version) {
        if (version == null) return "v1.0";
        Matcher m = TRAILING_DIGITS.matcher(version);
        if (!m.matches()) {
            return version;
        }
        return m.group(1) + (Long.parseLong(m.group(2)) + 1);
    }

    private static String joinStores(List<String> stores) {
        if (stores == null || stores.isEmpty()) return "ALL";
        List<String> out = new ArrayList<>();
        for (String s : stores) {
            if (!isBlank(s)) out.add(s.trim());
        }
        return out.isEmpty() ? "ALL" : String.join(",", out);
    }

    private static List<String> splitRefs(String csv) {
        if (isBlank(csv)) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private static String normalizePriority(String p) {
        if (isBlank(p)) return "MEDIUM";
        String u = p.trim().toUpperCase();
        return ("HIGH".equals(u) || "LOW".equals(u)) ? u : "MEDIUM";
    }

    private static LocalDate parseDate(String s) {
        if (isBlank(s)) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 新建模板步骤入参。 */
    public record StepCmd(String title, String desc, Boolean requirePhoto) {}
}
