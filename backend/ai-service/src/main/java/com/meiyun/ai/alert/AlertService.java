package com.meiyun.ai.alert;

import com.meiyun.ai.domain.AiAlertRule;
import com.meiyun.ai.domain.AiAlertRuleRepository;
import com.meiyun.ai.domain.AiInvokeLogRepository;
import com.meiyun.ai.quota.QuotaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 监控告警：无 @EnableScheduling，改为请求时基于 ai_invoke_log 实时评估各规则命中状态。
 * 指标：LATENCY_P99 / ERROR_RATE / CALL_COUNT / SUCCESS_RATE / QUOTA_WATERMARK。
 */
@Service
public class AlertService {

    private final AiAlertRuleRepository ruleRepo;
    private final AiInvokeLogRepository logRepo;
    private final QuotaService quotaService;

    public AlertService(AiAlertRuleRepository ruleRepo,
                        AiInvokeLogRepository logRepo,
                        QuotaService quotaService) {
        this.ruleRepo = ruleRepo;
        this.logRepo = logRepo;
        this.quotaService = quotaService;
    }

    public record AlertView(Long ruleId, String ruleCode, String ruleName, String metric,
                            String compareOp, BigDecimal thresholdNum, int windowMinutes,
                            String notifyChannel, boolean enabled,
                            boolean active, double currentValue, String status) {
    }

    @Transactional(readOnly = true)
    public List<AlertView> list() {
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .toLocalDate().atStartOfDay().atOffset(ZoneOffset.ofHours(8));
        long calls = logRepo.countByInvokedAtGreaterThanEqual(since);
        long fails = logRepo.countByInvokedAtGreaterThanEqualAndSuccess(since, false);
        Double p99d = logRepo.p99LatencySince(since);
        double p99 = p99d == null ? 0.0 : p99d;
        double errorRate = calls == 0 ? 0.0 : Math.round(fails * 10000.0 / calls) / 100.0;
        double successRate = calls == 0 ? 100.0 : Math.round((calls - fails) * 10000.0 / calls) / 100.0;
        double watermark = quotaService.maxWatermarkPercent();

        return ruleRepo.findAllByOrderByRuleIdAsc().stream()
                .map(r -> evaluate(r, calls, p99, errorRate, successRate, watermark))
                .toList();
    }

    /** KPI 卡片用：当前处于告警态的启用规则数。 */
    @Transactional(readOnly = true)
    public long activeCount() {
        return list().stream().filter(AlertView::active).count();
    }

    private AlertView evaluate(AiAlertRule r, long calls, double p99, double errorRate,
                               double successRate, double watermark) {
        double current = switch (r.getMetric()) {
            case "LATENCY_P99" -> p99;
            case "ERROR_RATE" -> errorRate;
            case "CALL_COUNT" -> calls;
            case "SUCCESS_RATE" -> successRate;
            case "QUOTA_WATERMARK" -> watermark;
            default -> 0.0;
        };
        double threshold = r.getThresholdNum().doubleValue();
        boolean hit = "<".equals(r.getCompareOp()) ? current < threshold : current > threshold;
        boolean active = Boolean.TRUE.equals(r.getEnabled()) && hit;
        return new AlertView(r.getRuleId(), r.getRuleCode(), r.getRuleName(), r.getMetric(),
                r.getCompareOp(), r.getThresholdNum(), r.getWindowMinutes(), r.getNotifyChannel(),
                Boolean.TRUE.equals(r.getEnabled()), active, current,
                !Boolean.TRUE.equals(r.getEnabled()) ? "已停用" : active ? "告警" : "正常");
    }
}
