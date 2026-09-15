package com.meiyun.finance;

import com.meiyun.security.SecurityContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 报表中心编排（B49 卡11，M1 集团屏 /m1-report）。
 *
 * <p><b>首卡收窄</b>：真实数据源仅 R01（门店营收日报，台账收银口径）/ R02（月度经营，revenue_monthly），
 * 其余模板 422「数据源待建」（登记 backlog）；format 仅 CSV 真实生成，XLSX/PDF 400（登记 backlog）。
 *
 * <p><b>generate/retry 非 @Transactional</b>：job 落库即提交（save 原子），异步线程立即可见；
 * 异步执行前把操作人 {@link com.meiyun.security.LoginUser} 显式传入 runner 复原数据域（防越权，
 * 见 ReportAsyncRunner 类注释）。
 *
 * <p><b>download 不校验属主</b>：任务列表本为共享视图，内容已按生成人数据域收敛（如实标注）。
 * 审计四 action：GENERATE / RETRY / DOWNLOAD / SUBSCRIBE（bizType=REPORT，payload 手写受控 JSON）。
 */
@Service
public class ReportService {

    /** 首卡真实数据源仅 R01/R02。 */
    static final Set<String> SUPPORTED = Set.of("R01", "R02");

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter VIEW_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int PREVIEW_LIMIT = 50;

    private final ReportTemplateRepository tplRepo;
    private final ReportJobRepository jobRepo;
    private final ReportCsvBuilder csvBuilder;
    private final ReportAsyncRunner asyncRunner;
    private final FinanceAuditRecorder audit;

    public ReportService(ReportTemplateRepository tplRepo, ReportJobRepository jobRepo,
                         ReportCsvBuilder csvBuilder, ReportAsyncRunner asyncRunner,
                         FinanceAuditRecorder audit) {
        this.tplRepo = tplRepo;
        this.jobRepo = jobRepo;
        this.csvBuilder = csvBuilder;
        this.asyncRunner = asyncRunner;
        this.audit = audit;
    }

    /** 下载载荷：文件名 + 含 BOM 字节。 */
    public record CsvDownload(String filename, byte[] content) {}

    // ==================== 只读 ====================

    /** 模板列表（id 升序；view key 照前端 mock：desc/dimensions/metrics 数组/lastRunAt 条件出现）。 */
    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReportTemplate t : tplRepo.findAllByOrderByIdAsc()) {
            out.add(view(t));
        }
        return out;
    }

    /** 生成历史（闭投影，createdAt 倒序；fileSize 字节→'248 KB' 串照 mock 展示形态）。 */
    public List<Map<String, Object>> jobs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReportJobRepository.ReportJobSummary s : jobRepo.findAllProjectedByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("templateId", s.getTemplateId());
            m.put("templateName", s.getTemplateName());
            m.put("category", s.getCategory());
            m.put("period", s.getPeriod());
            m.put("status", s.getStatus());
            m.put("format", s.getFormat());
            m.put("createdAt", s.getCreatedAt() == null ? null : VIEW_TS.format(s.getCreatedAt()));
            m.put("createdBy", s.getCreatedBy());
            if (s.getRowCount() != null) m.put("rowCount", s.getRowCount());
            if (s.getFileSize() != null) m.put("fileSize", fileSizeStr(s.getFileSize()));
            if (s.getError() != null) m.put("error", s.getError());
            out.add(m);
        }
        return out;
    }

    /** 数据预览（同步生成截 50 行；登录人数据域自动收敛）。 */
    public Map<String, Object> preview(String id, String period) {
        ReportTemplate t = tplRepo.findById(id)
                .orElseThrow(() -> err404("模板不存在：" + id));
        if (!SUPPORTED.contains(t.getId())) {
            throw err422("该模板数据源待建，已登记 backlog");
        }
        String p = resolvePeriod(t, period);
        validatePeriod(t, p);
        ReportCsvBuilder.CsvData data = csvBuilder.build(t.getId(), p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("headers", data.headers());
        m.put("rows", data.rows().size() > PREVIEW_LIMIT
                ? data.rows().subList(0, PREVIEW_LIMIT) : data.rows());
        return m;
    }

    // ==================== 写操作 ====================

    /** 订阅/退订（body.subscribed 缺省则取反；report:view 即可，与前端 toggle 语义一致）。 */
    public Map<String, Object> subscribe(String id, Map<String, Object> body) {
        ReportTemplate t = tplRepo.findById(id)
                .orElseThrow(() -> err404("模板不存在：" + id));
        boolean target = body != null && body.get("subscribed") != null
                ? Boolean.parseBoolean(String.valueOf(body.get("subscribed")))
                : !Boolean.TRUE.equals(t.getSubscribed());
        t.setSubscribed(target);
        tplRepo.save(t);
        audit.record("REPORT", t.getId(), operatorName(), "SUBSCRIBE",
                "{\"subscribed\":" + target + "}");
        return view(t);
    }

    /** 触发生成：建 GENERATING 任务 → 审计 → 异步生成（操作人上下文随参传递）。 */
    public Map<String, Object> generate(Map<String, Object> body) {
        String templateId = requireText(body, "templateId");
        ReportTemplate t = tplRepo.findById(templateId)
                .orElseThrow(() -> err404("模板不存在：" + templateId));
        if (!SUPPORTED.contains(t.getId())) {
            throw err422("该模板数据源待建，已登记 backlog");
        }
        String format = body.get("format") == null ? "CSV" : String.valueOf(body.get("format"));
        if (!"CSV".equalsIgnoreCase(format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "首卡仅支持 CSV 真实生成，XLSX/PDF 已登记 backlog");
        }
        String period = resolvePeriod(t, body.get("period") == null ? null : String.valueOf(body.get("period")));
        validatePeriod(t, period);

        ReportJob job = new ReportJob();
        job.setId(nextJobId());
        job.setTemplateId(t.getId());
        job.setTemplateName(t.getName());
        job.setCategory(t.getCategory());
        job.setPeriod(period);
        job.setStatus("GENERATING");
        job.setFormat("CSV");
        job.setCreatedAt(OffsetDateTime.now());
        job.setCreatedBy(operatorName());
        jobRepo.save(job);
        audit.record("REPORT", job.getId(), job.getCreatedBy(), "GENERATE",
                "{\"templateId\":\"" + t.getId() + "\",\"period\":\"" + period + "\",\"format\":\"CSV\"}");
        asyncRunner.run(job.getId(), SecurityContext.get());
        return jobView(job);
    }

    /** 失败重试：仅 FAILED 可重试；数据源收窄判断先于状态外的其余校验。 */
    public Map<String, Object> retry(String jobId) {
        ReportJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> err404("任务不存在：" + jobId));
        if (!"FAILED".equals(job.getStatus())) {
            throw err422("仅失败任务可重试");
        }
        if (!SUPPORTED.contains(job.getTemplateId())) {
            throw err422("该模板数据源待建，已登记 backlog");
        }
        job.setStatus("GENERATING");
        job.setError(null);
        jobRepo.save(job);
        audit.record("REPORT", job.getId(), operatorName(), "RETRY",
                "{\"templateId\":\"" + job.getTemplateId() + "\"}");
        asyncRunner.run(job.getId(), SecurityContext.get());
        return jobView(job);
    }

    /** 下载：content NULL（历史种子行）404 提示重新生成；GENERATING/FAILED 422。 */
    public CsvDownload download(String jobId) {
        ReportJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> err404("任务不存在：" + jobId));
        if ("GENERATING".equals(job.getStatus())) {
            throw err422("报表生成中，请稍后");
        }
        if ("FAILED".equals(job.getStatus())) {
            throw err422("报表生成失败，请重试");
        }
        if (job.getContent() == null || job.getContent().length == 0) {
            throw err404("历史文件未留存，请重新生成");
        }
        audit.record("REPORT", job.getId(), operatorName(), "DOWNLOAD",
                "{\"templateId\":\"" + job.getTemplateId() + "\",\"period\":\"" + job.getPeriod() + "\"}");
        String filename = job.getTemplateName() + "-" + job.getPeriod() + "-"
                + LocalDateTime.now().format(TS_FMT) + ".csv";
        return new CsvDownload(filename, job.getContent());
    }

    // ==================== 内部工具 ====================

    /** 模板 view（key 与前端 ReportTemplate 接口对齐：desc 而非 description）。 */
    private Map<String, Object> view(ReportTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("category", t.getCategory());
        m.put("desc", t.getDescription());
        m.put("period", t.getPeriod());
        m.put("dimensions", split(t.getDimensions()));
        m.put("metrics", split(t.getMetrics()));
        if (t.getLastRunAt() != null) {
            m.put("lastRunAt", VIEW_TS.format(t.getLastRunAt()));
        }
        m.put("subscribed", Boolean.TRUE.equals(t.getSubscribed()));
        return m;
    }

    /** 任务 view（generate/retry 返回实体路径，与列表投影同形）。 */
    private Map<String, Object> jobView(ReportJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("templateId", j.getTemplateId());
        m.put("templateName", j.getTemplateName());
        m.put("category", j.getCategory());
        m.put("period", j.getPeriod());
        m.put("status", j.getStatus());
        m.put("format", j.getFormat());
        m.put("createdAt", j.getCreatedAt() == null ? null : VIEW_TS.format(j.getCreatedAt()));
        m.put("createdBy", j.getCreatedBy());
        if (j.getRowCount() != null) m.put("rowCount", j.getRowCount());
        if (j.getFileSize() != null) m.put("fileSize", fileSizeStr(j.getFileSize()));
        if (j.getError() != null) m.put("error", j.getError());
        return m;
    }

    /** 期段解析：入参空时按模板周期给默认（日报→昨天，月报→上月）。 */
    private String resolvePeriod(ReportTemplate t, String period) {
        if (period != null && !period.isBlank()) {
            return period.trim();
        }
        if ("DAY".equals(t.getPeriod())) {
            return LocalDate.now().minusDays(1).toString();
        }
        if ("MONTH".equals(t.getPeriod())) {
            return YearMonth.now().minusMonths(1).toString();
        }
        throw err422("该模板需显式指定期段");
    }

    /** 期段格式校验：日报 yyyy-MM-dd、月报 yyyy-MM（其余周期首卡不支持真实生成）。 */
    private void validatePeriod(ReportTemplate t, String period) {
        try {
            if ("DAY".equals(t.getPeriod())) {
                LocalDate.parse(period);
                return;
            }
            if ("MONTH".equals(t.getPeriod())) {
                YearMonth.parse(period);
                return;
            }
        } catch (java.time.format.DateTimeParseException e) {
            // 落到统一中文错误
        }
        throw err422("期段格式不正确（日报需 yyyy-MM-dd / 月报需 yyyy-MM）");
    }

    /** 'J%02d' max+1 取号（只取 id 列；非 J 前缀忽略）。 */
    private String nextJobId() {
        int max = 0;
        for (String id : jobRepo.findAllIds()) {
            if (id != null && id.matches("J\\d+")) {
                max = Math.max(max, Integer.parseInt(id.substring(1)));
            }
        }
        return String.format(Locale.ROOT, "J%02d", max + 1);
    }

    /** 字节 → '248 KB' 展示串（照 mock 形态；<1KB 显 B）。 */
    static String fileSizeStr(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        return Math.round(bytes / 1024.0) + " KB";
    }

    private List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private String requireText(Map<String, Object> body, String key) {
        String v = body == null || body.get(key) == null ? null : String.valueOf(body.get(key)).trim();
        if (v == null || v.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少必填参数：" + key);
        }
        return v;
    }

    private String operatorName() {
        String name = SecurityContext.currentStaffName();
        if (name == null || name.isBlank()) {
            name = SecurityContext.currentStaffId();
        }
        return name == null || name.isBlank() ? "system" : name;
    }

    private static ResponseStatusException err404(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    private static ResponseStatusException err422(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

}
