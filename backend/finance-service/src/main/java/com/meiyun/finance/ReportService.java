package com.meiyun.finance;

import com.meiyun.security.SecurityContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ReportService {

    static final Set<String> SUPPORTED = Set.of("R01", "R02");

    private static final DateTimeFormatter VIEW_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int PREVIEW_LIMIT = 50;

    static final Map<String, String> CONTENT_TYPE = Map.of(
            "CSV", "text/csv; charset=UTF-8",
            "XLSX", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "PDF", "application/pdf");

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

    public record FileDownload(String filename, String contentType, byte[] content) {}

    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReportTemplate t : tplRepo.findAllByOrderByIdAsc()) {
            out.add(view(t));
        }
        return out;
    }

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
            m.put("createdAt", viewTs(s.getCreatedAt()));
            m.put("createdBy", s.getCreatedBy());
            if (s.getRowCount() != null) m.put("rowCount", s.getRowCount());
            if (s.getFileSize() != null) m.put("fileSize", fileSizeStr(s.getFileSize()));
            if (s.getError() != null) m.put("error", s.getError());
            if (s.getContentHash() != null) m.put("contentHash", s.getContentHash());
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> preview(String id, String period) {
        ReportTemplate t = tplRepo.findById(id)
                .orElseThrow(() -> err404("模板不存在：" + id));
        if (!SUPPORTED.contains(t.getId())) {
            throw err422("该模板数据源待建，已登记 backlog");
        }
        String p = resolvePeriod(t, period);
        validatePeriod(t, p);
        ReportCsvBuilder.CsvData data = csvBuilder.buildCsvData(t.getId(), p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("headers", data.headers());
        m.put("rows", data.rows().size() > PREVIEW_LIMIT
                ? data.rows().subList(0, PREVIEW_LIMIT) : data.rows());
        return m;
    }

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

    public Map<String, Object> generate(Map<String, Object> body) {
        String templateId = requireText(body, "templateId");
        ReportTemplate t = tplRepo.findById(templateId)
                .orElseThrow(() -> err404("模板不存在：" + templateId));
        if (!SUPPORTED.contains(t.getId())) {
            throw err422("该模板数据源待建，已登记 backlog");
        }
        String format = body.get("format") == null ? "CSV" : String.valueOf(body.get("format")).toUpperCase();
        if (!CONTENT_TYPE.containsKey(format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的导出格式：" + format);
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
        job.setFormat(format);
        job.setCreatedAt(OffsetDateTime.now());
        job.setCreatedBy(operatorName());
        jobRepo.save(job);
        audit.record("REPORT", job.getId(), job.getCreatedBy(), "GENERATE",
                "{\"templateId\":\"" + t.getId() + "\",\"period\":\"" + period + "\",\"format\":\"" + format + "\"}");
        asyncRunner.run(job.getId(), SecurityContext.get());
        return jobView(job);
    }

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

    public FileDownload download(String jobId) {
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
        String filename = job.getFileName() != null ? job.getFileName()
                : ReportCsvBuilder.downloadFileName(
                        job.getTemplateName(), job.getPeriod(), job.getCreatedAt(), job.getFormat());
        String contentType = CONTENT_TYPE.getOrDefault(job.getFormat(), "application/octet-stream");
        return new FileDownload(filename, contentType, job.getContent());
    }

    public Map<String, Object> verify(String jobId) {
        ReportJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> err404("任务不存在：" + jobId));
        if ("GENERATING".equals(job.getStatus())) {
            throw err422("报表生成中，请稍后");
        }
        if ("FAILED".equals(job.getStatus())) {
            throw err422("报表生成失败，请重试");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", job.getId());
        m.put("templateName", job.getTemplateName());
        m.put("period", job.getPeriod());
        m.put("fileName", job.getFileName());
        m.put("expectedHash", job.getContentHash());
        m.put("generatedAt", viewTs(job.getCreatedAt()));
        m.put("verifiedAt", viewTs(OffsetDateTime.now()));
        if (job.getContent() == null || job.getContent().length == 0) {
            m.put("ok", false);
            m.put("reason", "HISTORICAL_NOT_RETAINED");
            m.put("conclusion", "历史文件未留存，无法验真，请重新生成");
        } else {
            String actual = ReportCsvBuilder.sha256Hex(job.getContent());
            m.put("actualHash", actual);
            if (job.getContentHash() == null) {
                m.put("ok", false);
                m.put("reason", "HASH_NOT_RECORDED");
                m.put("conclusion", "该任务生成于验真功能上线前，未留存指纹，请重新生成后再验");
            } else {
                boolean ok = ReportCsvBuilder.hashEquals(job.getContentHash(), actual);
                m.put("ok", ok);
                m.put("reason", ok ? "MATCH" : "MISMATCH");
                m.put("conclusion", ok ? "哈希一致，文件内容与生成时完全一致" : "哈希不一致，文件内容已被篡改");
            }
        }
        audit.record("REPORT", job.getId(), operatorName(), "VERIFY",
                "{\"ok\":" + Boolean.TRUE.equals(m.get("ok"))
                        + ",\"reason\":\"" + m.get("reason") + "\"}");
        return m;
    }

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
            m.put("lastRunAt", viewTs(t.getLastRunAt()));
        }
        m.put("subscribed", Boolean.TRUE.equals(t.getSubscribed()));
        return m;
    }

    private Map<String, Object> jobView(ReportJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("templateId", j.getTemplateId());
        m.put("templateName", j.getTemplateName());
        m.put("category", j.getCategory());
        m.put("period", j.getPeriod());
        m.put("status", j.getStatus());
        m.put("format", j.getFormat());
        m.put("createdAt", viewTs(j.getCreatedAt()));
        m.put("createdBy", j.getCreatedBy());
        if (j.getRowCount() != null) m.put("rowCount", j.getRowCount());
        if (j.getFileSize() != null) m.put("fileSize", fileSizeStr(j.getFileSize()));
        if (j.getError() != null) m.put("error", j.getError());
        if (j.getContentHash() != null) m.put("contentHash", j.getContentHash());
        return m;
    }

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

    private String nextJobId() {
        int max = 0;
        for (String id : jobRepo.findAllIds()) {
            if (id != null && id.matches("J\\d+")) {
                max = Math.max(max, Integer.parseInt(id.substring(1)));
            }
        }
        return String.format(Locale.ROOT, "J%02d", max + 1);
    }

    private static String viewTs(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(CN_ZONE).format(VIEW_TS);
    }

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
