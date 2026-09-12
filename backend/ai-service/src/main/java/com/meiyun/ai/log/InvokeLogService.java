package com.meiyun.ai.log;

import com.meiyun.ai.domain.AiFeatureBindingRepository;
import com.meiyun.ai.domain.AiInvokeLog;
import com.meiyun.ai.domain.AiInvokeLogRepository;
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

    public InvokeLogService(AiInvokeLogRepository logRepo,
                            AiFeatureBindingRepository bindingRepo) {
        this.logRepo = logRepo;
        this.bindingRepo = bindingRepo;
    }

    public record LogView(Long logId, OffsetDateTime invokedAt, String staffId, String staffName,
                          String storeCode, String featureCode, String featureName,
                          String providerCode, String modelCode,
                          String promptSnippet, String outputSnippet,
                          Integer promptTokens, Integer completionTokens, Integer totalTokens,
                          Long latencyMs, boolean success, String errorCode, Long costFen) {
    }

    public record FeatureBill(String featureCode, String featureName,
                              long calls, long tokens, long costFen) {
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

    @Transactional(readOnly = true)
    public List<FeatureBill> monthlyBill() {
        Map<String, String> names = new LinkedHashMap<>();
        bindingRepo.findAll().forEach(b -> names.put(b.getFeatureCode(), b.getFeatureName()));
        return logRepo.costByFeature().stream()
                .map(c -> new FeatureBill(
                        c.getFeatureCode(),
                        names.getOrDefault(c.getFeatureCode(), FeatureCatalog.nameOf(cz(c.getFeatureCode()))),
                        nz(c.getCalls()), nz(c.getTokens()), nz(c.getCostFen())))
                .toList();
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
                0L, featureCount, enabledFeatureCount,
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
