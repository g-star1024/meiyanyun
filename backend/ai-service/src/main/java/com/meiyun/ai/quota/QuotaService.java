package com.meiyun.ai.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiInvokeLogRepository;
import com.meiyun.ai.domain.AiModel;
import com.meiyun.ai.domain.AiModelRepository;
import com.meiyun.ai.domain.AiQuota;
import com.meiyun.ai.domain.AiQuotaRepository;
import com.meiyun.ai.feature.FeatureCatalog;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 调用配额：额度存 ai_quota，用量实时聚合自 append-only 的 ai_invoke_log（唯一真相）。
 * 出站前按 FEATURE → MODEL → GLOBAL 顺序检查，命中具体维度即据此判定；超限 429 中文拒绝，不落日志。
 */
@Service
public class QuotaService {

    public static final String SCOPE_FEATURE = "FEATURE";
    public static final String SCOPE_MODEL = "MODEL";
    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String GLOBAL_TARGET = "*";

    private final AiQuotaRepository quotaRepo;
    private final AiInvokeLogRepository logRepo;
    private final AiModelRepository modelRepo;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public QuotaService(AiQuotaRepository quotaRepo,
                        AiInvokeLogRepository logRepo,
                        AiModelRepository modelRepo,
                        AuditRecorder audit) {
        this.quotaRepo = quotaRepo;
        this.logRepo = logRepo;
        this.modelRepo = modelRepo;
        this.audit = audit;
    }

    public record QuotaCmd(Integer dailyLimit, Integer monthlyLimit, Boolean enabled) {
    }

    public record QuotaView(Long quotaId, String quotaScope, String targetCode, String targetName,
                            Integer dailyLimit, Integer monthlyLimit,
                            long dailyUsed, long monthlyUsed, boolean enabled,
                            String updatedBy, OffsetDateTime updatedAt) {
    }

    public record SaveResult(boolean changed, Long quotaId, String quotaScope, String targetCode) {
    }

    @PostConstruct
    @Transactional
    public void seedDefaults() {
        ensure(SCOPE_GLOBAL, GLOBAL_TARGET);
        for (FeatureCatalog.Feature f : FeatureCatalog.FEATURES) {
            ensure(SCOPE_FEATURE, f.code());
        }
    }

    private AiQuota ensure(String scope, String target) {
        return quotaRepo.findByQuotaScopeAndTargetCode(scope, target).orElseGet(() -> {
            AiQuota q = new AiQuota();
            q.setQuotaScope(scope);
            q.setTargetCode(target);
            q.setDailyLimit(null);
            q.setMonthlyLimit(null);
            q.setEnabled(true);
            q.setUpdatedAt(OffsetDateTime.now());
            return quotaRepo.save(q);
        });
    }

    /** 配额管理列表：六功能 + 全局 + 所有已登记模型（模型行未建时返回默认「不限」视图，保存即落库）。 */
    @Transactional(readOnly = true)
    public List<QuotaView> list() {
        Map<String, AiQuota> rows = new LinkedHashMap<>();
        for (AiQuota q : quotaRepo.findAllByOrderByQuotaScopeAscTargetCodeAsc()) {
            rows.put(key(q.getQuotaScope(), q.getTargetCode()), q);
        }
        OffsetDateTime todayStart = todayStart();
        OffsetDateTime monthStart = monthStart();

        List<QuotaView> views = new ArrayList<>();
        for (FeatureCatalog.Feature f : FeatureCatalog.FEATURES) {
            views.add(toView(rows.get(key(SCOPE_FEATURE, f.code())), SCOPE_FEATURE, f.code(), f.name(),
                    todayStart, monthStart));
        }
        for (AiModel m : modelRepo.findAll()) {
            views.add(toView(rows.get(key(SCOPE_MODEL, m.getModelCode())), SCOPE_MODEL, m.getModelCode(),
                    m.getDisplayName(), todayStart, monthStart));
        }
        views.add(toView(rows.get(key(SCOPE_GLOBAL, GLOBAL_TARGET)), SCOPE_GLOBAL, GLOBAL_TARGET,
                "全局兜底", todayStart, monthStart));
        return views;
    }

    /** 出站前配额校验：FEATURE → MODEL → GLOBAL；任一启用维度超限即 429。 */
    @Transactional(readOnly = true)
    public void check(String featureCode, String modelCode) {
        OffsetDateTime todayStart = todayStart();
        OffsetDateTime monthStart = monthStart();
        checkOne(SCOPE_FEATURE, featureCode, FeatureCatalog.nameOf(featureCode), todayStart, monthStart);
        if (modelCode != null && !modelCode.isBlank()) {
            AiModel m = modelRepo.findAll().stream()
                    .filter(x -> modelCode.equals(x.getModelCode())).findFirst().orElse(null);
            checkOne(SCOPE_MODEL, modelCode, m == null ? modelCode : m.getDisplayName(),
                    todayStart, monthStart);
        }
        checkOne(SCOPE_GLOBAL, GLOBAL_TARGET, "全局", todayStart, monthStart);
    }

    private void checkOne(String scope, String target, String name,
                          OffsetDateTime todayStart, OffsetDateTime monthStart) {
        AiQuota q = quotaRepo.findByQuotaScopeAndTargetCode(scope, target).orElse(null);
        if (q == null || !Boolean.TRUE.equals(q.getEnabled())) {
            return;
        }
        if (q.getDailyLimit() != null) {
            long used = usedSince(scope, target, todayStart);
            if (used >= q.getDailyLimit()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "「" + name + "」今日调用额度已用尽（已用 " + used + "/" + q.getDailyLimit()
                                + " 次），请次日再试或联系管理员调整配额");
            }
        }
        if (q.getMonthlyLimit() != null) {
            long used = usedSince(scope, target, monthStart);
            if (used >= q.getMonthlyLimit()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "「" + name + "」本月调用额度已用尽（已用 " + used + "/" + q.getMonthlyLimit()
                                + " 次），请次月再试或联系管理员调整配额");
            }
        }
    }

    /** 告警用：当前配额最高水位百分比（0~100，不限额度维度不参与），无任何额度返回 0。 */
    @Transactional(readOnly = true)
    public double maxWatermarkPercent() {
        OffsetDateTime todayStart = todayStart();
        OffsetDateTime monthStart = monthStart();
        double max = 0.0;
        for (AiQuota q : quotaRepo.findAll()) {
            if (!Boolean.TRUE.equals(q.getEnabled())) {
                continue;
            }
            if (q.getDailyLimit() != null && q.getDailyLimit() > 0) {
                max = Math.max(max, 100.0 * usedSince(q.getQuotaScope(), q.getTargetCode(), todayStart)
                        / q.getDailyLimit());
            }
            if (q.getMonthlyLimit() != null && q.getMonthlyLimit() > 0) {
                max = Math.max(max, 100.0 * usedSince(q.getQuotaScope(), q.getTargetCode(), monthStart)
                        / q.getMonthlyLimit());
            }
        }
        return Math.round(max * 10.0) / 10.0;
    }

    private long usedSince(String scope, String target, OffsetDateTime since) {
        if (SCOPE_FEATURE.equals(scope)) {
            return logRepo.countByInvokedAtGreaterThanEqualAndFeatureCode(since, target);
        }
        if (SCOPE_MODEL.equals(scope)) {
            return logRepo.countByInvokedAtGreaterThanEqualAndModelCode(since, target);
        }
        return logRepo.countByInvokedAtGreaterThanEqual(since);
    }

    /** upsert：六功能/全局由种子保证存在，模型维度首次保存时建行。 */
    @Transactional
    public SaveResult save(String scope, String target, QuotaCmd cmd, String actor) {
        if (cmd == null) {
            throw badRequest("请求体不能为空");
        }
        String s = normalizeScope(scope);
        String t = (target == null || target.isBlank()) ? null : target.trim();
        if (t == null) {
            throw badRequest("配额目标编码不能为空");
        }
        Integer daily = normalizeLimit(cmd.dailyLimit(), "每日额度");
        Integer monthly = normalizeLimit(cmd.monthlyLimit(), "每月额度");
        boolean enabled = !Boolean.FALSE.equals(cmd.enabled());

        AiQuota q = quotaRepo.findByQuotaScopeAndTargetCode(s, t).orElse(null);
        boolean created = q == null;
        if (created) {
            q = new AiQuota();
            q.setQuotaScope(s);
            q.setTargetCode(t);
        }
        boolean changed = created
                || !Objects.equals(q.getDailyLimit(), daily)
                || !Objects.equals(q.getMonthlyLimit(), monthly)
                || Boolean.TRUE.equals(q.getEnabled()) != enabled;
        if (!changed) {
            return new SaveResult(false, q.getQuotaId(), s, t);
        }
        q.setDailyLimit(daily);
        q.setMonthlyLimit(monthly);
        q.setEnabled(enabled);
        q.setUpdatedBy(actor);
        q.setUpdatedAt(OffsetDateTime.now());
        q = quotaRepo.save(q);
        audit.record("AI_QUOTA", "QUOTA-" + s + "-" + t, actor, created ? "CREATE" : "UPDATE",
                payload(Map.of("scope", s, "target", t,
                        "dailyLimit", daily == null ? "" : daily,
                        "monthlyLimit", monthly == null ? "" : monthly,
                        "enabled", enabled)));
        return new SaveResult(true, q.getQuotaId(), s, t);
    }

    private QuotaView toView(AiQuota q, String scope, String target, String name,
                             OffsetDateTime todayStart, OffsetDateTime monthStart) {
        long dailyUsed = usedSince(scope, target, todayStart);
        long monthlyUsed = usedSince(scope, target, monthStart);
        if (q == null) {
            return new QuotaView(null, scope, target, name, null, null,
                    dailyUsed, monthlyUsed, true, null, null);
        }
        return new QuotaView(q.getQuotaId(), scope, q.getQuotaScope().equals(scope) ? target : q.getTargetCode(),
                name, q.getDailyLimit(), q.getMonthlyLimit(), dailyUsed, monthlyUsed,
                Boolean.TRUE.equals(q.getEnabled()), q.getUpdatedBy(), q.getUpdatedAt());
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw badRequest("配额维度不能为空");
        }
        String s = scope.trim().toUpperCase();
        if (!SCOPE_FEATURE.equals(s) && !SCOPE_MODEL.equals(s) && !SCOPE_GLOBAL.equals(s)) {
            throw badRequest("配额维度仅支持 FEATURE/MODEL/GLOBAL");
        }
        return s;
    }

    private Integer normalizeLimit(Integer v, String label) {
        if (v == null) {
            return null;
        }
        if (v < 0) {
            throw badRequest(label + "不能为负数");
        }
        return v;
    }

    private static OffsetDateTime todayStart() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8))
                .toLocalDate().atStartOfDay().atOffset(ZoneOffset.ofHours(8));
    }

    private static OffsetDateTime monthStart() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8))
                .with(TemporalAdjusters.firstDayOfMonth())
                .toLocalDate().atStartOfDay().atOffset(ZoneOffset.ofHours(8));
    }

    private static String key(String scope, String target) {
        return scope + "::" + target;
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
