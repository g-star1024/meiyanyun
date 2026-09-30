package com.meiyun.ai.log;

import com.meiyun.ai.alert.AlertService;
import com.meiyun.ai.domain.AiFeatureBindingRepository;
import com.meiyun.ai.domain.AiInvokeLog;
import com.meiyun.ai.domain.AiInvokeLogRepository;
import com.meiyun.ai.domain.AiInvokeLogRepository.FeatureCost;
import com.meiyun.ai.feature.FeatureCatalog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InvokeLogService {

    private static final int PAGE_MAX = 200;

    private final AiInvokeLogRepository logRepo;
    private final AiFeatureBindingRepository bindingRepo;
    private final AlertService alertService;

    public InvokeLogService(AiInvokeLogRepository logRepo,
                            AiFeatureBindingRepository bindingRepo,
                            AlertService alertService) {
        this.logRepo = logRepo;
        this.bindingRepo = bindingRepo;
        this.alertService = alertService;
    }

    public record LogView(Long logId, OffsetDateTime invokedAt, String staffId, String staffName,
                          String storeCode, String featureCode, String featureName,
                          String providerCode, String modelCode,
                          String promptSnippet, String outputSnippet,
                          Integer promptTokens, Integer completionTokens, Integer totalTokens,
                          Long latencyMs, boolean success, String errorCode, Long costFen) {
    }

    public record FeatureBill(String featureCode, String featureName,
                              long calls, long tokens, long costFen,
                              long prevCalls, long prevTokens, long prevCostFen,
                              Double callsMomPct, Double tokensMomPct, Double costMomPct) {
    }

    public record KpiView(long todayCalls, double successRate, Long p99LatencyMs,
                          long activeAlerts, long featureCount, long enabledFeatureCount,
                          long monthCalls, long totalCostFen, long modelCount,
                          long pendingApprovals, long monthApproved) {
    }

    @Transactional(readOnly = true)
    public Page<LogView> search(String featureCode, Boolean success, int page, int size) {
        int s = Math.min(Math.max(size, 1), PAGE_MAX);
        Pageable pageable = PageRequest.of(Math.max(page, 0), s);
        String fc = (featureCode == null || featureCode.isBlank()) ? null : featureCode.trim();
        Page<AiInvokeLog> result =
                fc == null && success == null
                        ? logRepo.findAllByOrderByLogIdDesc(pageable)
                        : logRepo.search(fc, success, pageable);
        return result.map(this::toView);
    }

    /** 月度账单：按功能汇总调用量与费用（分），金额按模型定价折算；
     *  口径：东八区自然月、success=true 成功调用；附上月同口径与环比（上月为 0 则环比 null，不伪造）。 */
    @Transactional(readOnly = true)
    public List<FeatureBill> monthlyBill() {
        OffsetDateTime monthStart = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .toLocalDate().withDayOfMonth(1).atStartOfDay().atOffset(ZoneOffset.ofHours(8));
        OffsetDateTime nextMonth = monthStart.plusMonths(1);
        OffsetDateTime prevMonth = monthStart.minusMonths(1);
        Map<String, FeatureCost> cur = new LinkedHashMap<>();
        logRepo.costByFeatureBetween(monthStart, nextMonth)
                .forEach(c -> cur.put(c.getFeatureCode(), c));
        Map<String, FeatureCost> prev = new LinkedHashMap<>();
        logRepo.costByFeatureBetween(prevMonth, monthStart)
                .forEach(c -> prev.put(c.getFeatureCode(), c));
        Map<String, String> names = new LinkedHashMap<>();
        bindingRepo.findAll().forEach(b -> names.put(b.getFeatureCode(), b.getFeatureName()));
        return cur.values().stream()
                .map(c -> {
                    FeatureCost p = prev.get(c.getFeatureCode());
                    long pc = p == null ? 0 : nz(p.getCalls());
                    long pt = p == null ? 0 : nz(p.getTokens());
                    long pf = p == null ? 0 : nz(p.getCostFen());
                    long cc = nz(c.getCalls());
                    long ct = nz(c.getTokens());
                    long cf = nz(c.getCostFen());
                    return new FeatureBill(
                            c.getFeatureCode(),
                            names.getOrDefault(c.getFeatureCode(), FeatureCatalog.nameOf(cz(c.getFeatureCode()))),
                            cc, ct, cf, pc, pt, pf,
                            momPct(cc, pc), momPct(ct, pt), momPct(cf, pf));
                })
                .toList();
    }

    private static Double momPct(long cur, long prev) {
        if (prev == 0) return null;
        return Math.round((cur - prev) * 10000.0 / prev) / 100.0;
    }

    /** 统一 KPI：A1Gateway 取前四项，A1Admin 取功能/用量/费用/模型，A1Govern 取审批三项。 */
    @Transactional(readOnly = true)
    public KpiView kpi(long pendingApprovals, long monthApproved, long modelCount) {
        OffsetDateTime todayStart = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .toLocalDate().atStartOfDay().atOffset(ZoneOffset.ofHours(8));
        OffsetDateTime monthStart = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .with(TemporalAdjusters.firstDayOfMonth())
                .toLocalDate().atStartOfDay().atOffset(ZoneOffset.ofHours(8));

        long todayCalls = logRepo.countByInvokedAtGreaterThanEqual(todayStart);
        long todayFail = logRepo.countByInvokedAtGreaterThanEqualAndSuccess(todayStart, false);
        double successRate = todayCalls == 0 ? 100.0
                : Math.round((todayCalls - todayFail) * 10000.0 / todayCalls) / 100.0;
        Double p99 = logRepo.p99LatencySince(todayStart);
        long monthCalls = logRepo.countByInvokedAtGreaterThanEqual(monthStart);
        long totalCostFen = logRepo.sumCostFen();

        long featureCount = bindingRepo.count();
        long enabledFeatureCount = bindingRepo.findAll().stream()
                .filter(b -> Boolean.TRUE.equals(b.getEnabled())).count();

        return new KpiView(todayCalls, successRate, p99 == null ? null : Math.round(p99),
                alertService.activeCount(), featureCount, enabledFeatureCount,
                monthCalls, totalCostFen, modelCount,
                pendingApprovals, monthApproved);
    }

    private LogView toView(AiInvokeLog l) {
        return new LogView(
                l.getLogId(),
                l.getInvokedAt(),
                l.getStaffId(),
                l.getStaffName(),
                l.getStoreCode(),
                l.getFeatureCode(),
                FeatureCatalog.nameOf(l.getFeatureCode()),
                l.getProviderCode(),
                l.getModelCode(),
                l.getPromptSnippet(),
                l.getOutputSnippet(),
                l.getPromptTokens(),
                l.getCompletionTokens(),
                l.getTotalTokens(),
                l.getLatencyMs(),
                Boolean.TRUE.equals(l.getSuccess()),
                l.getErrorCode(),
                l.getCostFen() == null ? 0L : l.getCostFen());
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static String cz(String s) {
        return s == null ? "" : s;
    }
}
