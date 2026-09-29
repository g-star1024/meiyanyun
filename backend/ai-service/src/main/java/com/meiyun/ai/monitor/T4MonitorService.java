package com.meiyun.ai.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiT4AlertEvent;
import com.meiyun.ai.domain.AiT4AlertEventRepository;
import com.meiyun.ai.domain.AiT4AlertRule;
import com.meiyun.ai.domain.AiT4AlertRuleRepository;
import com.meiyun.ai.domain.AiT4Model;
import com.meiyun.ai.domain.AiT4ModelMetric;
import com.meiyun.ai.domain.AiT4ModelMetricRepository;
import com.meiyun.ai.domain.AiT4ModelRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * T4 监控告警：模型实时指标快照 + 告警规则 CRUD/启停 + 告警事件确认/解决闭环。
 *
 * 诚实口径：
 * 1. 指标为 uk(model_code) 一对一最新快照，无真实采集探针（种子快照，逐字 mock）；
 * 2. 规则/事件 modelCode 引用卡1 模型仓库业务码（mdl-churn/mdl-skin/mdl-nlp/mdl-rec/mdl-sales），禁改名；
 * 3. 状态机逐字 mock：ack 仅 FIRING→ACKNOWLEDGED 记 acknowledgedAt/By；resolve 非 RESOLVED→RESOLVED
 *    记 resolvedAt，未确认则补登 ack 字段（acknowledgedAt=resolvedAt·acknowledgedBy=操作人）；
 *    mock 对非法迁移静默返回，后端收窄中文 400（照卡1 非 READY 禁发/卡3 发布闸门先例）；
 * 4. 新规则置顶展示由前端适配层负责（后端按 rule_id 升序直返，照卡2/卡3 先例）；
 * 5. ack/resolve 无权限码照 mock 仅登录（契约④零新码：写权限仅 rule:create/rule:edit 两枚方法级）。
 */
@Service
public class T4MonitorService {

    private static final int CODE_MAX = 40;
    private static final int NAME_MAX = 64;
    private static final int CHANNEL_MAX = 16;
    private static final int CHANNELS_MAX = 8;
    private static final Set<String> METRICS = Set.of("DRIFT", "LATENCY", "ERROR_RATE", "ACCURACY", "QPS_DROP");
    private static final Set<String> OPERATORS = Set.of(">", "<", ">=", "<=");
    private static final Set<String> SEVERITIES = Set.of("CRITICAL", "WARNING", "INFO");
    private static final Map<String, String> STATUS_LABEL = Map.of(
            "FIRING", "告警中", "ACKNOWLEDGED", "已确认", "RESOLVED", "已解决");

    private final AiT4AlertRuleRepository ruleRepo;
    private final AiT4AlertEventRepository eventRepo;
    private final AiT4ModelMetricRepository metricRepo;
    private final AiT4ModelRepository modelRepo;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public T4MonitorService(AiT4AlertRuleRepository ruleRepo,
                            AiT4AlertEventRepository eventRepo,
                            AiT4ModelMetricRepository metricRepo,
                            AiT4ModelRepository modelRepo,
                            AuditRecorder audit) {
        this.ruleRepo = ruleRepo;
        this.eventRepo = eventRepo;
        this.metricRepo = metricRepo;
        this.modelRepo = modelRepo;
        this.audit = audit;
    }

    public record MetricView(String modelId, String modelName, OffsetDateTime timestamp,
                             int qps, int latencyP99, double errorRate, double driftScore, double accuracy) {
    }

    public record RuleView(String id, String name, String modelId, String modelName, String metric,
                           double threshold, String operator, String severity, boolean enabled,
                           List<String> notifyChannels, OffsetDateTime createdAt) {
    }

    public record EventView(String id, String ruleId, String ruleName, String modelId, String modelName,
                            String severity, String status, String message, double value, double threshold,
                            OffsetDateTime triggeredAt, OffsetDateTime acknowledgedAt,
                            OffsetDateTime resolvedAt, String acknowledgedBy) {
    }

    /** 监控总览（前端 load 一次拉取：metrics + rules + events 三契约） */
    public record MonitorOverview(List<MetricView> metrics, List<RuleView> rules, List<EventView> events) {
    }

    public record CreateRuleCmd(String name, String modelId, String modelName, String metric,
                                Double threshold, String operator, String severity,
                                Boolean enabled, List<String> notifyChannels) {
    }

    public record UpdateRuleCmd(String name, String metric, Double threshold, String operator,
                                String severity, List<String> notifyChannels) {
    }

    public record ToggleCmd(Boolean enabled) {
    }

    @Transactional(readOnly = true)
    public MonitorOverview overview() {
        List<MetricView> metrics = metricRepo.findAllByOrderByMetricIdAsc().stream()
                .map(this::toMetricView).toList();
        List<RuleView> rules = ruleRepo.findAllByOrderByRuleIdAsc().stream()
                .map(this::toRuleView).toList();
        List<EventView> events = eventRepo.findAllByOrderByEventIdAsc().stream()
                .map(this::toEventView).toList();
        return new MonitorOverview(metrics, rules, events);
    }

    // 刻意不加方法级事务：save 由仓储自身事务提交，随后 findByCode 全新读回时间戳（照卡1 register 先例）。
    public RuleView createRule(CreateRuleCmd cmd, String actor) {
        String name = normalizeName(cmd == null ? null : cmd.name());
        if (ruleRepo.existsByName(name)) {
            throw badRequest("告警规则名称已存在（" + name + "）");
        }
        String modelId = normalizeModelId(cmd == null ? null : cmd.modelId());
        AiT4Model model = modelRepo.findByCode(modelId)
                .orElseThrow(() -> badRequest("模型不存在（code=" + modelId + "）"));
        String modelName = cmd != null && cmd.modelName() != null && !cmd.modelName().isBlank()
                ? cmd.modelName().trim() : model.getName();
        String metric = normalizeMetric(cmd == null ? null : cmd.metric());
        double threshold = normalizeThreshold(cmd == null ? null : cmd.threshold());
        String operator = normalizeOperator(cmd == null ? null : cmd.operator());
        String severity = normalizeSeverity(cmd == null ? null : cmd.severity());
        List<String> channels = normalizeChannels(cmd == null ? null : cmd.notifyChannels());

        AiT4AlertRule r = new AiT4AlertRule();
        r.setCode(nextCode("rule", ruleRepo::existsByCode));
        r.setName(name);
        r.setModelCode(modelId);
        r.setModelName(modelName);
        r.setMetric(metric);
        r.setThreshold(BigDecimal.valueOf(threshold));
        r.setOperator(operator);
        r.setSeverity(severity);
        r.setEnabled(cmd == null || !Boolean.FALSE.equals(cmd.enabled()));
        r.setNotifyChannels(writeChannels(channels));
        AiT4AlertRule saved = ruleRepo.save(r);

        audit.record("AI_T4_MONITOR", saved.getCode(), actor, "RULE_CREATE",
                payload(Map.of("name", name, "metric", metric, "operator", operator, "threshold", threshold)));
        return ruleRepo.findByCode(saved.getCode()).map(this::toRuleView)
                .orElseGet(() -> toRuleView(saved));
    }

    /** 更新规则：patch 语义（仅覆盖非 null 字段，照 mock updateRule Object.assign） */
    @Transactional
    public RuleView updateRule(String code, UpdateRuleCmd cmd, String actor) {
        AiT4AlertRule r = mustGetRule(code);
        if (cmd == null) {
            throw badRequest("更新内容不能为空");
        }
        if (cmd.name() != null) {
            String name = normalizeName(cmd.name());
            if (!name.equals(r.getName()) && ruleRepo.existsByName(name)) {
                throw badRequest("告警规则名称已存在（" + name + "）");
            }
            r.setName(name);
        }
        if (cmd.metric() != null) {
            r.setMetric(normalizeMetric(cmd.metric()));
        }
        if (cmd.threshold() != null) {
            r.setThreshold(BigDecimal.valueOf(normalizeThreshold(cmd.threshold())));
        }
        if (cmd.operator() != null) {
            r.setOperator(normalizeOperator(cmd.operator()));
        }
        if (cmd.severity() != null) {
            r.setSeverity(normalizeSeverity(cmd.severity()));
        }
        if (cmd.notifyChannels() != null) {
            r.setNotifyChannels(writeChannels(normalizeChannels(cmd.notifyChannels())));
        }
        ruleRepo.save(r);
        audit.record("AI_T4_MONITOR", r.getCode(), actor, "RULE_UPDATE", payload(Map.of("name", r.getName())));
        return toRuleView(r);
    }

    /** 启停开关：仅翻 enabled（逐字 mock toggleRule） */
    @Transactional
    public RuleView toggleRule(String code, ToggleCmd cmd, String actor) {
        AiT4AlertRule r = mustGetRule(code);
        if (cmd == null || cmd.enabled() == null) {
            throw badRequest("启用开关不能为空");
        }
        r.setEnabled(cmd.enabled());
        ruleRepo.save(r);
        audit.record("AI_T4_MONITOR", r.getCode(), actor, "RULE_TOGGLE",
                payload(Map.of("name", r.getName(), "enabled", cmd.enabled())));
        return toRuleView(r);
    }

    /** 确认：仅 FIRING → ACKNOWLEDGED（mock 静默返回，后端收窄中文 400） */
    @Transactional
    public EventView acknowledge(String code, String actor) {
        AiT4AlertEvent e = mustGetEvent(code);
        if (!"FIRING".equals(e.getStatus())) {
            throw badRequest("仅「告警中」的告警可确认（当前：" + statusLabel(e.getStatus()) + "）");
        }
        e.setStatus("ACKNOWLEDGED");
        e.setAcknowledgedAt(OffsetDateTime.now());
        e.setAcknowledgedBy(actor);
        eventRepo.save(e);
        audit.record("AI_T4_MONITOR", e.getCode(), actor, "ACK",
                payload(Map.of("ruleName", e.getRuleName(), "modelName", e.getModelName())));
        return toEventView(e);
    }

    /** 解决：非 RESOLVED → RESOLVED 记 resolvedAt；未确认则补登 ack 字段（逐字 mock resolveAlert） */
    @Transactional
    public EventView resolve(String code, String actor) {
        AiT4AlertEvent e = mustGetEvent(code);
        if ("RESOLVED".equals(e.getStatus())) {
            throw badRequest("告警已解决，无需重复操作");
        }
        OffsetDateTime now = OffsetDateTime.now();
        e.setStatus("RESOLVED");
        e.setResolvedAt(now);
        if (e.getAcknowledgedAt() == null) {
            e.setAcknowledgedAt(now);
            e.setAcknowledgedBy(actor);
        }
        eventRepo.save(e);
        audit.record("AI_T4_MONITOR", e.getCode(), actor, "RESOLVE",
                payload(Map.of("ruleName", e.getRuleName(), "modelName", e.getModelName())));
        return toEventView(e);
    }

    private AiT4AlertRule mustGetRule(String code) {
        String c = code == null ? "" : code.trim();
        return ruleRepo.findByCode(c)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "告警规则不存在（code=" + c + "）"));
    }

    private AiT4AlertEvent mustGetEvent(String code) {
        String c = code == null ? "" : code.trim();
        return eventRepo.findByCode(c)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "告警事件不存在（code=" + c + "）"));
    }

    /** 业务码分配：前缀 + 时间戳 36 进制 + 1 随机位，uk 冲突重试（照 mdl-/quota-/feat- 先例，8 次足够） */
    private String nextCode(String prefix, java.util.function.Predicate<String> exists) {
        for (int i = 0; i < 8; i++) {
            String c = prefix + "-" + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString(ThreadLocalRandom.current().nextInt(36), 36);
            if (c.length() <= CODE_MAX && !exists.test(c)) {
                return c;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "业务编码分配失败，请重试");
    }

    // ---------- 视图 ----------

    private MetricView toMetricView(AiT4ModelMetric m) {
        return new MetricView(m.getModelCode(), m.getModelName(), m.getSnapshotAt(),
                m.getQps() == null ? 0 : m.getQps(),
                m.getLatencyP99() == null ? 0 : m.getLatencyP99(),
                num(m.getErrorRate()), num(m.getDriftScore()), num(m.getAccuracy()));
    }

    private RuleView toRuleView(AiT4AlertRule r) {
        return new RuleView(r.getCode(), r.getName(), r.getModelCode(), r.getModelName(), r.getMetric(),
                num(r.getThreshold()), r.getOperator(), r.getSeverity(),
                Boolean.TRUE.equals(r.getEnabled()), readChannels(r.getNotifyChannels()), r.getCreatedAt());
    }

    private EventView toEventView(AiT4AlertEvent e) {
        return new EventView(e.getCode(), e.getRuleCode(), e.getRuleName(), e.getModelCode(), e.getModelName(),
                e.getSeverity(), e.getStatus(), e.getMessage(), num(e.getValue()), num(e.getThreshold()),
                e.getTriggeredAt(), e.getAcknowledgedAt(), e.getResolvedAt(), e.getAcknowledgedBy());
    }

    private static double num(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }

    // ---------- 校验 ----------

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw badRequest("告警规则名称不能为空");
        }
        String t = name.trim();
        if (t.length() > NAME_MAX) {
            throw badRequest("告警规则名称不能超过 " + NAME_MAX + " 字");
        }
        return t;
    }

    private String normalizeModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw badRequest("监控模型不能为空");
        }
        return modelId.trim();
    }

    private String normalizeMetric(String metric) {
        if (metric == null || metric.isBlank()) {
            throw badRequest("监控指标不能为空");
        }
        String t = metric.trim().toUpperCase();
        if (!METRICS.contains(t)) {
            throw badRequest("监控指标仅支持 DRIFT（漂移分数）/ LATENCY（P99 延迟）/ ERROR_RATE（错误率）/ ACCURACY（准确率）/ QPS_DROP（QPS 下跌）");
        }
        return t;
    }

    private double normalizeThreshold(Double threshold) {
        if (threshold == null || !Double.isFinite(threshold)) {
            throw badRequest("阈值不能为空且须为有限数值");
        }
        return threshold;
    }

    private String normalizeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw badRequest("比较符不能为空");
        }
        String t = operator.trim();
        if (!OPERATORS.contains(t)) {
            throw badRequest("比较符仅支持 > / < / >= / <=");
        }
        return t;
    }

    private String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            throw badRequest("告警级别不能为空");
        }
        String t = severity.trim().toUpperCase();
        if (!SEVERITIES.contains(t)) {
            throw badRequest("告警级别仅支持 CRITICAL（严重）/ WARNING（警告）/ INFO（提示）");
        }
        return t;
    }

    private List<String> normalizeChannels(List<String> channels) {
        if (channels == null || channels.isEmpty()) {
            throw badRequest("通知渠道不能为空");
        }
        List<String> t = channels.stream().map(c -> c == null ? "" : c.trim()).filter(c -> !c.isEmpty()).toList();
        if (t.isEmpty()) {
            throw badRequest("通知渠道不能为空");
        }
        if (t.size() > CHANNELS_MAX) {
            throw badRequest("通知渠道不能超过 " + CHANNELS_MAX + " 个");
        }
        for (String c : t) {
            if (c.length() > CHANNEL_MAX) {
                throw badRequest("单个通知渠道不能超过 " + CHANNEL_MAX + " 字");
            }
        }
        return t;
    }

    private String writeChannels(List<String> channels) {
        try {
            return json.writeValueAsString(channels);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readChannels(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String statusLabel(String status) {
        return STATUS_LABEL.getOrDefault(status, status);
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private String payload(Map<String, ?> data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
