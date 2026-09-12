package com.meiyun.ai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiEvalTask;
import com.meiyun.ai.domain.AiEvalTaskRepository;
import com.meiyun.ai.domain.AiExperiment;
import com.meiyun.ai.domain.AiExperimentRepository;
import com.meiyun.ai.domain.AiFeatureBinding;
import com.meiyun.ai.domain.AiFeatureBindingRepository;
import com.meiyun.ai.domain.AiInvokeLogRepository;
import com.meiyun.ai.domain.AiModel;
import com.meiyun.ai.domain.AiModelRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 效果评估与 A/B 实验：技术指标快照来自 ai_invoke_log 实时聚合，
 * 业务转化/ROI 无系统数据源，结论（conclusion）由运营人工录入。
 */
@Service
public class EvalService {

    private static final ZoneOffset BJ = ZoneOffset.ofHours(8);

    private final AiEvalTaskRepository taskRepo;
    private final AiExperimentRepository experimentRepo;
    private final AiInvokeLogRepository logRepo;
    private final AiFeatureBindingRepository bindingRepo;
    private final AiModelRepository modelRepo;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public EvalService(AiEvalTaskRepository taskRepo,
                       AiExperimentRepository experimentRepo,
                       AiInvokeLogRepository logRepo,
                       AiFeatureBindingRepository bindingRepo,
                       AiModelRepository modelRepo,
                       AuditRecorder audit) {
        this.taskRepo = taskRepo;
        this.experimentRepo = experimentRepo;
        this.logRepo = logRepo;
        this.bindingRepo = bindingRepo;
        this.modelRepo = modelRepo;
        this.audit = audit;
    }

    // ============================ DTO ============================

    public record Metrics(long calls, long successCalls, double successRate, Double p99LatencyMs,
                          long tokens, long costFen) {
    }

    public record EvalView(Long taskId, String taskName, String evalScope, String targetCode, String targetName,
                           int windowDays, OffsetDateTime periodStart, OffsetDateTime periodEnd,
                           Metrics metrics, String status, String conclusion,
                           String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record EvalCreateCmd(String taskName, String evalScope, String targetCode, Integer windowDays) {
    }

    public record ConclusionCmd(String conclusion) {
    }

    public record ExperimentView(Long experimentId, String experimentName, String controlModel,
                                 String experimentModel, int windowDays,
                                 OffsetDateTime periodStart, OffsetDateTime periodEnd,
                                 Metrics controlMetrics, Metrics experimentMetrics,
                                 BigDecimal liftPp, String status, String conclusion,
                                 String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record ExperimentCreateCmd(String experimentName, String controlModel, String experimentModel,
                                      Integer windowDays) {
    }

    // ============================ 评估任务 ============================

    @Transactional(readOnly = true)
    public List<EvalView> listTasks() {
        return taskRepo.findAllByOrderByTaskIdDesc().stream().map(this::toTaskView).toList();
    }

    @Transactional
    public EvalView createTask(EvalCreateCmd cmd, String actor) {
        if (cmd == null || cmd.taskName() == null || cmd.taskName().isBlank()) {
            throw badRequest("评估任务名称不能为空");
        }
        if (cmd.taskName().trim().length() > 128) {
            throw badRequest("评估任务名称不能超过 128 字");
        }
        String scope = cmd.evalScope() == null ? "" : cmd.evalScope().trim().toUpperCase();
        if (!"FEATURE".equals(scope) && !"MODEL".equals(scope)) {
            throw badRequest("评估维度仅支持 FEATURE/MODEL");
        }
        if (cmd.targetCode() == null || cmd.targetCode().isBlank()) {
            throw badRequest("评估目标编码不能为空");
        }
        int days = normalizeDays(cmd.windowDays());
        String targetCode = cmd.targetCode().trim();
        String targetName = resolveTargetName(scope, targetCode);

        OffsetDateTime end = OffsetDateTime.now(BJ);
        OffsetDateTime start = end.minusDays(days);
        Metrics metrics = snapshot(scope, targetCode, start);

        AiEvalTask t = new AiEvalTask();
        t.setTaskName(cmd.taskName().trim());
        t.setEvalScope(scope);
        t.setTargetCode(targetCode);
        t.setTargetName(targetName);
        t.setWindowDays(days);
        t.setPeriodStart(start);
        t.setPeriodEnd(end);
        t.setMetricsJson(write(metricsMap(metrics)));
        t.setStatus("RUNNING");
        t.setCreatedBy(actor);
        AiEvalTask saved = taskRepo.save(t);
        audit.record("AI_EVAL", "EVAL-" + saved.getTaskId(), actor, "CREATE",
                payload(Map.of("name", saved.getTaskName(), "scope", scope,
                        "targetCode", targetCode, "windowDays", days, "calls", metrics.calls())));
        return toTaskView(saved);
    }

    /** RUNNING 任务按「当前时刻回看窗口」重新聚合并刷新快照；DONE 任务冻结，中文拒绝。 */
    @Transactional
    public EvalView refreshTask(Long id, String actor) {
        AiEvalTask t = taskRepo.findById(id).orElseThrow(() -> notFound("评估任务不存在（id=" + id + "）"));
        if ("DONE".equals(t.getStatus())) {
            throw badRequest("评估任务已结案，快照冻结不可刷新");
        }
        OffsetDateTime end = OffsetDateTime.now(BJ);
        OffsetDateTime start = end.minusDays(t.getWindowDays());
        Metrics metrics = snapshot(t.getEvalScope(), t.getTargetCode(), start);
        t.setPeriodStart(start);
        t.setPeriodEnd(end);
        t.setMetricsJson(write(metricsMap(metrics)));
        AiEvalTask saved = taskRepo.save(t);
        audit.record("AI_EVAL", "EVAL-" + id, actor, "REFRESH",
                payload(Map.of("calls", metrics.calls(), "successRate", metrics.successRate())));
        return toTaskView(saved);
    }

    @Transactional
    public EvalView concludeTask(Long id, ConclusionCmd cmd, String actor) {
        if (cmd == null || cmd.conclusion() == null || cmd.conclusion().isBlank()) {
            throw badRequest("评估结论不能为空");
        }
        if (cmd.conclusion().trim().length() > 512) {
            throw badRequest("评估结论不能超过 512 字");
        }
        AiEvalTask t = taskRepo.findById(id).orElseThrow(() -> notFound("评估任务不存在（id=" + id + "）"));
        t.setStatus("DONE");
        t.setConclusion(cmd.conclusion().trim());
        taskRepo.save(t);
        audit.record("AI_EVAL", "EVAL-" + id, actor, "CONCLUDE",
                payload(Map.of("conclusion", t.getConclusion())));
        return toTaskView(t);
    }

    @Transactional(readOnly = true)
    public long runningTaskCount() {
        return taskRepo.countByStatus("RUNNING");
    }

    // ============================ A/B 实验 ============================

    @Transactional(readOnly = true)
    public List<ExperimentView> listExperiments() {
        return experimentRepo.findAllByOrderByExperimentIdDesc().stream().map(this::toExperimentView).toList();
    }

    @Transactional
    public ExperimentView createExperiment(ExperimentCreateCmd cmd, String actor) {
        if (cmd == null || cmd.experimentName() == null || cmd.experimentName().isBlank()) {
            throw badRequest("实验名称不能为空");
        }
        if (cmd.experimentName().trim().length() > 128) {
            throw badRequest("实验名称不能超过 128 字");
        }
        if (cmd.controlModel() == null || cmd.controlModel().isBlank()
                || cmd.experimentModel() == null || cmd.experimentModel().isBlank()) {
            throw badRequest("对照组与实验组模型编码均不能为空");
        }
        String control = cmd.controlModel().trim();
        String experiment = cmd.experimentModel().trim();
        if (control.equals(experiment)) {
            throw badRequest("对照组与实验组模型不能相同");
        }
        if (!modelExists(control)) {
            throw badRequest("对照组模型不存在：" + control);
        }
        if (!modelExists(experiment)) {
            throw badRequest("实验组模型不存在：" + experiment);
        }
        int days = normalizeDays(cmd.windowDays());
        OffsetDateTime end = OffsetDateTime.now(BJ);
        OffsetDateTime start = end.minusDays(days);
        Metrics controlMetrics = snapshot("MODEL", control, start);
        Metrics experimentMetrics = snapshot("MODEL", experiment, start);
        BigDecimal lift = BigDecimal.valueOf(experimentMetrics.successRate() - controlMetrics.successRate())
                .setScale(2, RoundingMode.HALF_UP);

        AiExperiment e = new AiExperiment();
        e.setExperimentName(cmd.experimentName().trim());
        e.setControlModel(control);
        e.setExperimentModel(experiment);
        e.setWindowDays(days);
        e.setPeriodStart(start);
        e.setPeriodEnd(end);
        e.setControlMetrics(write(metricsMap(controlMetrics)));
        e.setExperimentMetrics(write(metricsMap(experimentMetrics)));
        e.setLiftPp(lift);
        e.setStatus("RUNNING");
        e.setCreatedBy(actor);
        AiExperiment saved = experimentRepo.save(e);
        audit.record("AI_EXPERIMENT", "EXP-" + saved.getExperimentId(), actor, "CREATE",
                payload(Map.of("name", saved.getExperimentName(), "control", control,
                        "experiment", experiment, "liftPp", lift)));
        return toExperimentView(saved);
    }

    @Transactional
    public ExperimentView refreshExperiment(Long id, String actor) {
        AiExperiment e = experimentRepo.findById(id)
                .orElseThrow(() -> notFound("A/B 实验不存在（id=" + id + "）"));
        if ("FINISHED".equals(e.getStatus())) {
            throw badRequest("实验已结案，快照冻结不可刷新");
        }
        OffsetDateTime end = OffsetDateTime.now(BJ);
        OffsetDateTime start = end.minusDays(e.getWindowDays());
        Metrics controlMetrics = snapshot("MODEL", e.getControlModel(), start);
        Metrics experimentMetrics = snapshot("MODEL", e.getExperimentModel(), start);
        BigDecimal lift = BigDecimal.valueOf(experimentMetrics.successRate() - controlMetrics.successRate())
                .setScale(2, RoundingMode.HALF_UP);
        e.setPeriodStart(start);
        e.setPeriodEnd(end);
        e.setControlMetrics(write(metricsMap(controlMetrics)));
        e.setExperimentMetrics(write(metricsMap(experimentMetrics)));
        e.setLiftPp(lift);
        experimentRepo.save(e);
        audit.record("AI_EXPERIMENT", "EXP-" + id, actor, "REFRESH", payload(Map.of("liftPp", lift)));
        return toExperimentView(e);
    }

    @Transactional
    public ExperimentView concludeExperiment(Long id, ConclusionCmd cmd, String actor) {
        if (cmd == null || cmd.conclusion() == null || cmd.conclusion().isBlank()) {
            throw badRequest("实验结论不能为空");
        }
        if (cmd.conclusion().trim().length() > 512) {
            throw badRequest("实验结论不能超过 512 字");
        }
        AiExperiment e = experimentRepo.findById(id)
                .orElseThrow(() -> notFound("A/B 实验不存在（id=" + id + "）"));
        e.setStatus("FINISHED");
        e.setConclusion(cmd.conclusion().trim());
        experimentRepo.save(e);
        audit.record("AI_EXPERIMENT", "EXP-" + id, actor, "CONCLUDE",
                payload(Map.of("conclusion", e.getConclusion())));
        return toExperimentView(e);
    }

    @Transactional(readOnly = true)
    public long runningExperimentCount() {
        return experimentRepo.countByStatus("RUNNING");
    }

    /** KPI 用：已结案实验的平均提升百分点，无结案实验返回 null（前端展示 —）。 */
    @Transactional(readOnly = true)
    public BigDecimal avgLiftPp() {
        List<AiExperiment> finished = experimentRepo.findAllByOrderByExperimentIdDesc().stream()
                .filter(e -> "FINISHED".equals(e.getStatus()) && e.getLiftPp() != null)
                .toList();
        if (finished.isEmpty()) {
            return null;
        }
        BigDecimal sum = finished.stream().map(AiExperiment::getLiftPp).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(finished.size()), 2, RoundingMode.HALF_UP);
    }

    // ============================ 内部方法 ============================

    private Metrics snapshot(String scope, String targetCode, OffsetDateTime since) {
        String feature = "FEATURE".equals(scope) ? targetCode : null;
        String model = "MODEL".equals(scope) ? targetCode : null;
        AiInvokeLogRepository.MetricAgg agg = logRepo.aggregateSince(since, feature, model);
        long calls = agg.getCalls() == null ? 0 : agg.getCalls();
        long success = agg.getSuccessCalls() == null ? 0 : agg.getSuccessCalls();
        long tokens = agg.getTokens() == null ? 0 : agg.getTokens();
        long costFen = agg.getCostFen() == null ? 0 : agg.getCostFen();
        double rate = calls == 0 ? 0.0 : Math.round(success * 10000.0 / calls) / 100.0;
        Double p99d = logRepo.p99LatencySince(since, feature, model);
        Double p99 = p99d == null ? null : Math.round(p99d * 10.0) / 10.0;
        return new Metrics(calls, success, rate, p99, tokens, costFen);
    }

    private String resolveTargetName(String scope, String code) {
        if ("FEATURE".equals(scope)) {
            AiFeatureBinding b = bindingRepo.findByFeatureCode(code).orElse(null);
            if (b == null) {
                throw badRequest("评估功能不存在：" + code);
            }
            return b.getFeatureName();
        }
        List<AiModel> models = modelRepo.findAllByOrderByPriorityAscModelIdDesc();
        return models.stream().filter(m -> code.equals(m.getModelCode())).findFirst()
                .map(AiModel::getDisplayName)
                .orElseThrow(() -> badRequest("评估模型不存在：" + code));
    }

    private boolean modelExists(String modelCode) {
        return modelRepo.findAllByOrderByPriorityAscModelIdDesc().stream()
                .anyMatch(m -> modelCode.equals(m.getModelCode()));
    }

    private int normalizeDays(Integer days) {
        if (days == null) {
            return 7;
        }
        if (days < 1 || days > 90) {
            throw badRequest("评估窗口天数需在 1~90 之间");
        }
        return days;
    }

    private EvalView toTaskView(AiEvalTask t) {
        Metrics m = readMetrics(t.getMetricsJson());
        return new EvalView(t.getTaskId(), t.getTaskName(), t.getEvalScope(), t.getTargetCode(),
                t.getTargetName(), t.getWindowDays(), t.getPeriodStart(), t.getPeriodEnd(), m,
                t.getStatus(), t.getConclusion(), t.getCreatedBy(), t.getCreatedAt(), t.getUpdatedAt());
    }

    private ExperimentView toExperimentView(AiExperiment e) {
        return new ExperimentView(e.getExperimentId(), e.getExperimentName(), e.getControlModel(),
                e.getExperimentModel(), e.getWindowDays(), e.getPeriodStart(), e.getPeriodEnd(),
                readMetrics(e.getControlMetrics()), readMetrics(e.getExperimentMetrics()),
                e.getLiftPp(), e.getStatus(), e.getConclusion(), e.getCreatedBy(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private Map<String, Object> metricsMap(Metrics m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("calls", m.calls());
        map.put("successCalls", m.successCalls());
        map.put("successRate", m.successRate());
        map.put("p99LatencyMs", m.p99LatencyMs());
        map.put("tokens", m.tokens());
        map.put("costFen", m.costFen());
        return map;
    }

    private Metrics readMetrics(String raw) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = json.readValue(raw == null || raw.isBlank() ? "{}" : raw, Map.class);
            return new Metrics(
                    asLong(m.get("calls")), asLong(m.get("successCalls")),
                    asDouble(m.get("successRate")),
                    m.get("p99LatencyMs") == null ? null : asDouble(m.get("p99LatencyMs")),
                    asLong(m.get("tokens")), asLong(m.get("costFen")));
        } catch (Exception e) {
            return new Metrics(0, 0, 0.0, null, 0, 0);
        }
    }

    private long asLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private double asDouble(Object o) {
        return o == null ? 0.0 : ((Number) o).doubleValue();
    }

    private String write(Map<String, ?> data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String payload(Map<String, ?> data) {
        return write(data);
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
