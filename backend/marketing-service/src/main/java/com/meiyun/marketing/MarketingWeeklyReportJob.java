package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 营销周报定时推送（P5-B94 D8，DealBackfillJob 范式）。
 *
 * <p>每周一 09:00（Asia/Shanghai，cron 可外部化 {@code meiyun.marketing-weekly.cron}，
 * 对齐 M2SettingsView「每周一 9:00」文案口径）扫描 enabled 订阅行，复用
 * {@link MarketingStatsService#overview()} 组装上周（周一~周日）中文摘要，
 * 经 RestTemplate + X-Internal-Token POST txn {@code /api/txn/internal/marketing-weekly}
 * （LevelDowngradeNotifier 软降级先例：单条 try/catch 容错续跑，不阻塞其余订阅），
 * 成功后回写 last_sent_week=周戳。
 *
 * <p>双防重（D12）：txn 侧 Notification idemKey={@code MKT-WEEKLY:{weekStamp}:{staffNo}} UK
 * ＋本侧 last_sent_week 回补——重启/多实例同周重跑不重复触达。
 * 有产出（sent>0 或 failed>0）才落单条 SYSTEM 审计 MARKETING_WEEKLY+RUN；
 * 无订阅行或全量同周已推送（skipped）空跑零审计防噪音。
 */
@Component
public class MarketingWeeklyReportJob {

    private static final Logger log = LoggerFactory.getLogger(MarketingWeeklyReportJob.class);
    private static final String ACTOR = "SYSTEM";
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MD_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final MarketingWeeklySubRepository subRepo;
    private final MarketingStatsService statsService;
    private final RestTemplate restTemplate;
    private final AuditRecorder audit;

    @Value("${txn.service.url:http://localhost:8083}")
    private String txnBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public MarketingWeeklyReportJob(MarketingWeeklySubRepository subRepo,
                                    MarketingStatsService statsService,
                                    RestTemplate restTemplate,
                                    AuditRecorder audit) {
        this.subRepo = subRepo;
        this.statsService = statsService;
        this.restTemplate = restTemplate;
        this.audit = audit;
    }

    @Scheduled(cron = "${meiyun.marketing-weekly.cron:0 0 9 * * MON}", zone = "Asia/Shanghai")
    public void run() {
        LocalDate today = LocalDate.now(BIZ_ZONE);
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastMonday = thisMonday.minusWeeks(1);
        LocalDate lastSunday = thisMonday.minusDays(1);
        String weekStamp = lastMonday.get(IsoFields.WEEK_BASED_YEAR)
                + "-W" + String.format("%02d", lastMonday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));

        List<MarketingWeeklySub> subs = subRepo.findByEnabledTrue();
        if (subs.isEmpty()) {
            return;
        }
        Map<String, Object> ov = statsService.overview();
        String title = "营销周报 " + weekStamp + "（"
                + lastMonday.format(MD_FMT) + "~" + lastSunday.format(MD_FMT) + "）";
        String content = buildSummary(ov);

        int sent = 0;
        int failed = 0;
        int skipped = 0;
        for (MarketingWeeklySub sub : subs) {
            if (weekStamp.equals(sub.getLastSentWeek())) {
                skipped++;
                continue;
            }
            try {
                postWeekly(sub.getStaffNo(), weekStamp, title, content);
                sub.setLastSentWeek(weekStamp);
                subRepo.save(sub);
                sent++;
            } catch (Exception ex) {
                failed++;
                log.warn("营销周报推送失败（软降级，不影响其余订阅）：staffNo={} {}",
                        sub.getStaffNo(), ex.getMessage());
            }
        }
        if (sent > 0 || failed > 0) {
            audit.record("MARKETING_WEEKLY", "WEEK-" + weekStamp, ACTOR, "RUN",
                    "{\"weekStamp\":\"" + weekStamp + "\",\"subs\":" + subs.size()
                            + ",\"sent\":" + sent + ",\"failed\":" + failed
                            + ",\"skipped\":" + skipped + "}");
            log.info("营销周报推送完成 {}：订阅{} 推送{} 失败{} 同周跳过{}",
                    weekStamp, subs.size(), sent, failed, skipped);
        }
    }

    /** 上周经营摘要（overview 六块抽关键指标，单行中文 ≤500 字，txn 侧 truncate 兜底）。 */
    @SuppressWarnings("unchecked")
    private String buildSummary(Map<String, Object> ov) {
        Map<String, Object> coupon = (Map<String, Object>) ov.get("coupon");
        Map<String, Object> campaign = (Map<String, Object>) ov.get("campaign");
        Map<String, Object> push = (Map<String, Object>) ov.get("push");
        Map<String, Object> channel = (Map<String, Object>) ov.get("channel");
        List<Map<String, Object>> channelRows = channel == null
                ? List.of() : (List<Map<String, Object>>) channel.get("rows");
        String top = "暂无";
        if (channelRows != null && !channelRows.isEmpty()) {
            Map<String, Object> c0 = channelRows.get(0);
            top = c0.get("name") + "（营收 " + yuan(c0.get("revenue")) + " 元）";
        }
        return "上周营销概览：发券 " + coupon.get("totalIssued") + " 张、核销 " + coupon.get("totalUsed")
                + " 张（核销率 " + pct(coupon.get("writeoffRate")) + "%）；活动 " + campaign.get("campaignCount")
                + " 个（进行中 " + campaign.get("runningCount") + "），投放 " + yuan(campaign.get("totalSpent"))
                + " 元、成交 " + yuan(campaign.get("totalActualAmount")) + " 元（综合 ROI "
                + campaign.get("overallRoi") + "）；全域触达 " + push.get("delivered") + "、互动 "
                + push.get("clicked") + "、成交 " + push.get("converted") + " 单；渠道营收冠军：" + top
                + "。明细见营销总览。";
    }

    /** 单条投递 txn 内部端点（X-Internal-Token 系统身份；失败抛异常由调用方软降级计数）。 */
    private void postWeekly(String staffNo, String weekStamp, String title, String content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        Map<String, Object> body = Map.of(
                "staffNo", staffNo,
                "weekStamp", weekStamp,
                "title", title,
                "content", content);
        restTemplate.exchange(txnBaseUrl + "/api/txn/internal/marketing-weekly",
                HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    /** Long 分 → 元字符串（两位小数）。 */
    private static String yuan(Object v) {
        if (v == null) return "0.00";
        return String.format(Locale.ROOT, "%.2f", ((Number) v).longValue() / 100.0);
    }

    /** 0~1 比率 → 百分比一位小数。 */
    private static String pct(Object v) {
        if (v == null) return "0.0";
        return String.format(Locale.ROOT, "%.1f", ((Number) v).doubleValue() * 100);
    }
}
