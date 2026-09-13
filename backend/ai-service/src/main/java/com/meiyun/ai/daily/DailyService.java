package com.meiyun.ai.daily;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.client.TxnDailyClient;
import com.meiyun.ai.domain.AiDailyReport;
import com.meiyun.ai.domain.AiDailyReportRepository;
import com.meiyun.ai.domain.AiDailySubscription;
import com.meiyun.ai.domain.AiDailySubscriptionRepository;
import com.meiyun.ai.domain.AiDailySuggestion;
import com.meiyun.ai.domain.AiDailySuggestionRepository;
import com.meiyun.ai.domain.AiInvokeLogRepository;
import com.meiyun.ai.feature.FeatureInvokeService;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 经营日报（A1-05）：指标由 txn-service 经 X-Internal-Token 内部端点投影（Asia/Shanghai
 * 自然日真实聚合：已收款营收/到店登记/首单新客/已完成退款与风控异常，另取上一自然日做环比），
 * 走 daily 功能 invoke 全治理链（角色灰度矩阵 / 门店灰度 / 敏感词 / 配额 / 计费 / ai_invoke_log），
 * LLM 输出容错解析为「经营摘要 + 核心/异常/行动建议」后一报多行沉淀 ai_daily_report /
 * ai_daily_suggestion；同日期+门店可重生成（新版本多行，读时取最新一版）。
 *
 * <p>诚实口径：建议必须由当日真实指标支撑（不得编造库存/客诉/行为埋点等无数据源事实，模型不可及时
 * 用真实数值规则兜底）；采纳建议仅为站内幂等登记（真实任务下发为远期 Backlog，与流失干预同边界）；
 * 「每日自动推送」本期仅落员工偏好，站内/企微/短信/邮件四通道无真实定时出站，推送状态一律如实置灰
 * （企微/短信/邮件真实触达 M5-03 为远期 Backlog）；无报告不编造摘要，前端据空态诚实引导。
 */
@Service
public class DailyService {

    public static final String FEATURE_CODE = "daily";
    public static final String MODEL_VERSION = "v1-2026-09";

    private static final ZoneOffset BJ = ZoneOffset.ofHours(8);
    private static final int RAW_MAX = 8000;
    private static final int METRICS_MAX = 4000;
    private static final int SUMMARY_MAX = 1000;
    private static final int TITLE_MAX = 128;
    private static final int DETAIL_MAX = 1000;
    private static final int DATE_MAX = 16;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 四通道推送能力（本期仅偏好登记，无真实出站）；顺序即前端行序。 */
    private static final List<ChannelView> CHANNELS = List.of(
            new ChannelView("inbox", "站内消息", "disabled", "未发送",
                    "本期仅登记订阅偏好，站内自动推送待任务通道建设（M5-03 远期）"),
            new ChannelView("wechat", "企业微信", "disabled", "未发送",
                    "企业微信真实触达通道为远期 Backlog（M5-03）"),
            new ChannelView("sms", "短信", "disabled", "未发送",
                    "短信真实触达通道为远期 Backlog（M5-03）"),
            new ChannelView("email", "邮件", "disabled", "未发送",
                    "邮件真实触达通道为远期 Backlog（M5-03）"));

    private final AiDailyReportRepository reportRepo;
    private final AiDailySuggestionRepository suggestionRepo;
    private final AiDailySubscriptionRepository subscriptionRepo;
    private final AiInvokeLogRepository invokeLogRepo;
    private final FeatureInvokeService featureInvokeService;
    private final TxnDailyClient txnClient;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public DailyService(AiDailyReportRepository reportRepo,
                        AiDailySuggestionRepository suggestionRepo,
                        AiDailySubscriptionRepository subscriptionRepo,
                        AiInvokeLogRepository invokeLogRepo,
                        FeatureInvokeService featureInvokeService,
                        TxnDailyClient txnClient,
                        AuditRecorder audit) {
        this.reportRepo = reportRepo;
        this.suggestionRepo = suggestionRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.invokeLogRepo = invokeLogRepo;
        this.featureInvokeService = featureInvokeService;
        this.txnClient = txnClient;
        this.audit = audit;
    }

    // ============================ DTO ============================

    public record GenerateCmd(String date, String storeCode) {
    }

    public record MetricsView(String date, String store, boolean available,
                              Long revenueFen, Long paidOrderCount, Long arrivalCount, Long newCustomerCount,
                              Long refundCount, Long refundFen, Long contraYellowCount, Long contraRedCount,
                              Long anomalyCount, Long prevRevenueFen, Long prevArrivalCount,
                              Long prevNewCustomerCount, Long prevAnomalyCount,
                              Integer revenueDeltaPct, Integer arrivalDeltaPct,
                              Integer newCustomerDeltaPct, Integer anomalyDeltaPct) {

        /** 交易域不可用降级视图：available=false、计数全 0、环比不可算；不造任何业务数。 */
        public static MetricsView empty(String date, String store) {
            return new MetricsView(date, store, false,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    null, null, null, null);
        }
    }

    public record SuggestionView(Long suggestionId, String type, String title, String detail,
                                 boolean adopted, OffsetDateTime adoptedAt, String adoptedBy) {
    }

    public record ReportView(Long reportId, String date, String storeCode, String summary,
                             String modelCode, Long invokeLogId, Integer totalTokens, Long costFen,
                             boolean adoptedAll, List<SuggestionView> suggestions,
                             OffsetDateTime createdAt, String generatedAt) {
    }

    public record GenerateResult(ReportView report, MetricsView metrics) {
    }

    public record HistoryItem(String date, String summary, Long revenueFen, Long arrivalCount,
                              Long newCustomerCount, Long anomalyCount,
                              OffsetDateTime createdAt, String generatedAt) {
    }

    public record DailyStats(long reportCount, long suggestionCount, long adoptedCount,
                             long weekInvokes, String modelVersion, String modelNote) {
    }

    public record ChannelView(String channel, String channelName, String status, String statusLabel,
                              String note) {
    }

    public record SubscriptionView(boolean subscribed) {
    }

    public record ActionResult(boolean changed, Long suggestionId, String action) {
    }

    private record ParsedReport(String summary, List<ParsedSug> suggestions) {
    }

    private record ParsedSug(String type, String title, String detail) {
    }

    // ============================ 业务方法 ============================

    /** KPI/metrics：始终拉交易域当日+上一自然日真实指标（KPI 不依赖是否已生成日报）；服务不可用 available=false。 */
    public MetricsView metrics(String date, String storeCode) {
        LoginUser user = requireUser();
        String day = normalizeDate(date);
        String store = resolveStore(storeCode, user);
        Map<String, Object> today = txnClient.dailyMetrics(day, store);
        if (today == null) {
            return MetricsView.empty(day, store == null ? "ALL" : store);
        }
        String prev = LocalDate.parse(day).minusDays(1).format(DATE_FMT);
        Map<String, Object> y = txnClient.dailyMetrics(prev, store);
        long prevRevenue = y == null ? 0L : lng(y, "revenueFen");
        long prevArrival = y == null ? 0L : lng(y, "arrivalCount");
        long prevNew = y == null ? 0L : lng(y, "newCustomerCount");
        long prevAnomaly = y == null ? 0L : lng(y, "anomalyCount");
        long revenue = lng(today, "revenueFen");
        long arrival = lng(today, "arrivalCount");
        long newCust = lng(today, "newCustomerCount");
        long anomaly = lng(today, "anomalyCount");
        return new MetricsView(
                day, str(today.get("store")).isBlank() ? (store == null ? "ALL" : store) : str(today.get("store")),
                true, revenue, lng(today, "paidOrderCount"), arrival, newCust,
                lng(today, "refundCount"), lng(today, "refundFen"),
                lng(today, "contraYellowCount"), lng(today, "contraRedCount"), anomaly,
                prevRevenue, prevArrival, prevNew, prevAnomaly,
                pctChange(revenue, prevRevenue), pctChange(arrival, prevArrival),
                pctChange(newCust, prevNew), pctChange(anomaly, prevAnomaly));
    }

    /** 生成日报：拉当日真实指标 → daily invoke → 容错解析 → 一报多行落库（同键新版本）。无方法级事务。 */
    public GenerateResult generate(GenerateCmd cmd) {
        LoginUser user = requireUser();
        String day = normalizeDate(cmd == null ? null : cmd.date());
        String store = resolveStore(cmd == null ? null : cmd.storeCode(), user);

        Map<String, Object> today = txnClient.dailyMetrics(day, store);
        if (today == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "交易服务暂不可用或未返回当日经营指标，请稍后重试生成日报");
        }
        String prev = LocalDate.parse(day).minusDays(1).format(DATE_FMT);
        Map<String, Object> y = txnClient.dailyMetrics(prev, store);
        MetricsView mv = buildMetrics(day, store, today, y);

        FeatureInvokeService.InvokeView v = featureInvokeService.invoke(
                FEATURE_CODE,
                new FeatureInvokeService.InvokeCmd(composePrompt(day, today, y), store == null ? "" : store));

        ParsedReport parsed = parse(v.content(), today, y);

        AiDailyReport r = new AiDailyReport();
        r.setReportDate(day);
        r.setStoreCode(store == null ? "" : store);
        r.setRevenueFen(mv.revenueFen());
        r.setPaidOrderCount(mv.paidOrderCount());
        r.setArrivalCount(mv.arrivalCount());
        r.setNewCustomerCount(mv.newCustomerCount());
        r.setRefundCount(mv.refundCount());
        r.setRefundFen(mv.refundFen());
        r.setContraYellowCount(mv.contraYellowCount());
        r.setContraRedCount(mv.contraRedCount());
        r.setAnomalyCount(mv.anomalyCount());
        r.setMetricsJson(writeMetrics(today, y));
        r.setSummary(truncate(parsed.summary(), SUMMARY_MAX));
        r.setRawOutput(truncate(v.content() == null ? "" : v.content(), RAW_MAX));
        r.setInvokeLogId(v.logId());
        r.setModelCode(v.modelCode());
        r.setTotalTokens(v.totalTokens());
        r.setCostFen(v.costFen() == null ? 0L : v.costFen());
        r.setStaffId(user.staffId());
        r.setStaffName(user.staffName());
        AiDailyReport saved = reportRepo.save(r);

        List<AiDailySuggestion> sugRows = new ArrayList<>();
        for (ParsedSug ps : parsed.suggestions()) {
            AiDailySuggestion s = new AiDailySuggestion();
            s.setReportId(saved.getReportId());
            s.setSuggestionType(ps.type());
            s.setTitle(truncate(ps.title(), TITLE_MAX));
            s.setDetail(truncate(ps.detail(), DETAIL_MAX));
            s.setStaffId(user.staffId());
            s.setStaffName(user.staffName());
            sugRows.add(s);
        }
        suggestionRepo.saveAll(sugRows);

        audit.record("AI_DAILY_REPORT", "DAILY-" + saved.getReportId(),
                DataScope.currentActor(), "GENERATE_DAILY",
                payload(Map.of("reportId", saved.getReportId(), "date", day,
                        "storeCode", store == null ? "" : store,
                        "revenueFen", mv.revenueFen(), "anomalyCount", mv.anomalyCount(),
                        "suggestions", sugRows.size())));

        return new GenerateResult(toReportView(saved, sugRows, false), mv);
    }

    /** 指定日期最新版日报（含建议）；无报告 404 中文引导先生成。 */
    @Transactional(readOnly = true)
    public ReportView report(String date, String storeCode) {
        LoginUser user = requireUser();
        String day = normalizeDate(date);
        String store = resolveStore(storeCode, user);
        AiDailyReport r = latest(day, store == null ? "" : store);
        if (r == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    day + " 尚未生成 AI 经营日报，请先选择日期并点击「生成日报」");
        }
        List<AiDailySuggestion> sugs = suggestionRepo.findByReportIdOrderBySuggestionIdAsc(r.getReportId());
        boolean adoptedAll = !sugs.isEmpty() && sugs.stream().allMatch(s -> Boolean.TRUE.equals(s.getAdopted()));
        return toReportView(r, sugs, adoptedAll);
    }

    /** 历史日报：按日期+门店归并取每日最新版，按日期倒序，最多 14 条。 */
    @Transactional(readOnly = true)
    public List<HistoryItem> history(String storeCode) {
        LoginUser user = requireUser();
        String store = resolveStore(storeCode, user);
        List<AiDailyReport> rows = reportRepo.findTop60ByOrderByReportIdDesc();
        Map<String, AiDailyReport> latestByKey = new LinkedHashMap<>();
        for (AiDailyReport r : rows) {
            String sc = r.getStoreCode() == null ? "" : r.getStoreCode();
            if (store != null && !store.equals(sc)) {
                continue;
            }
            latestByKey.putIfAbsent(r.getReportDate() + "|" + sc, r);
        }
        List<HistoryItem> out = new ArrayList<>();
        for (AiDailyReport r : latestByKey.values()) {
            out.add(new HistoryItem(r.getReportDate(), r.getSummary(),
                    r.getRevenueFen(), r.getArrivalCount(), r.getNewCustomerCount(), r.getAnomalyCount(),
                    r.getCreatedAt(), bjTime(r.getCreatedAt())));
            if (out.size() >= 14) {
                break;
            }
        }
        return out;
    }

    /** 页头 KPI 统计：报告/建议/采纳真实计数 + 本周 daily invoke + 诚实模型说明。 */
    @Transactional(readOnly = true)
    public DailyStats stats() {
        requireUser();
        OffsetDateTime weekStart = OffsetDateTime.now(BJ).with(DayOfWeek.MONDAY)
                .toLocalDate().atStartOfDay().atOffset(BJ);
        long weekInvokes = invokeLogRepo.countByInvokedAtGreaterThanEqualAndFeatureCode(weekStart, FEATURE_CODE);
        return new DailyStats(reportRepo.count(), suggestionRepo.count(), suggestionRepo.countByAdoptedTrue(),
                weekInvokes, MODEL_VERSION,
                "经营摘要与建议由 daily 功能真实出站生成，指标取自交易收款/到店登记/退款流水；建议采纳为站内登记，"
                        + "模型经营增益待历史样本回流后评估");
    }

    /** 推送通道能力：本期无真实定时出站，四通道一律如实置灰。 */
    public List<ChannelView> channels() {
        requireUser();
        return CHANNELS;
    }

    /** 读取登录人订阅偏好（无记录默认未订阅）。 */
    @Transactional(readOnly = true)
    public SubscriptionView subscription() {
        LoginUser user = requireUser();
        return new SubscriptionView(subscriptionRepo.findByStaffId(user.staffId())
                .map(AiDailySubscription::getSubscribed).orElse(false));
    }

    /** 切换订阅偏好（员工级 upsert，审计留痕；本期仅登记，无真实推送）。 */
    @Transactional
    public SubscriptionView toggleSubscription(Boolean subscribed) {
        LoginUser user = requireUser();
        boolean want = Boolean.TRUE.equals(subscribed);
        AiDailySubscription s = subscriptionRepo.findByStaffId(user.staffId()).orElseGet(() -> {
            AiDailySubscription n = new AiDailySubscription();
            n.setStaffId(user.staffId());
            return n;
        });
        boolean changed = !Boolean.TRUE.equals(s.getSubscribed()) == want || s.getSubscriptionId() == null;
        s.setSubscribed(want);
        s.setStoreCode(user.storeCode() == null ? "" : user.storeCode());
        s.setStaffName(user.staffName());
        subscriptionRepo.save(s);
        if (changed) {
            audit.record("AI_DAILY_REPORT", "SUB-" + user.staffId(),
                    DataScope.currentActor(), want ? "SUBSCRIBE_DAILY" : "UNSUBSCRIBE_DAILY",
                    payload(Map.of("staffId", user.staffId(), "subscribed", want)));
        }
        return new SubscriptionView(want);
    }

    /** 采纳建议（站内幂等登记）；真实任务下发为远期 Backlog。 */
    @Transactional
    public ActionResult adopt(Long suggestionId) {
        LoginUser user = requireUser();
        AiDailySuggestion s = suggestionRepo.findById(suggestionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "日报建议不存在（id=" + suggestionId + "）"));
        if (Boolean.TRUE.equals(s.getAdopted())) {
            return new ActionResult(false, suggestionId, "adopt");
        }
        s.setAdopted(true);
        s.setAdoptedAt(OffsetDateTime.now());
        s.setAdoptedBy(user.staffId());
        suggestionRepo.save(s);
        AiDailyReport r = reportRepo.findById(s.getReportId()).orElse(null);
        audit.record("AI_DAILY_REPORT", "SUG-" + suggestionId,
                DataScope.currentActor(), "ADOPT_SUGGESTION",
                payload(Map.of("suggestionId", suggestionId,
                        "date", r == null ? "" : r.getReportDate(),
                        "type", s.getSuggestionType(), "title", s.getTitle())));
        return new ActionResult(true, suggestionId, "adopt");
    }

    // ============================ 解析 / 组装 ============================

    private AiDailyReport latest(String day, String store) {
        return reportRepo.findByReportDateAndStoreCodeOrderByReportIdDesc(day, store).stream()
                .findFirst().orElse(null);
    }

    private MetricsView buildMetrics(String day, String store,
                                     Map<String, Object> today, Map<String, Object> prevMap) {
        long prevRevenue = prevMap == null ? 0L : lng(prevMap, "revenueFen");
        long prevArrival = prevMap == null ? 0L : lng(prevMap, "arrivalCount");
        long prevNew = prevMap == null ? 0L : lng(prevMap, "newCustomerCount");
        long prevAnomaly = prevMap == null ? 0L : lng(prevMap, "anomalyCount");
        long revenue = lng(today, "revenueFen");
        long arrival = lng(today, "arrivalCount");
        long newCust = lng(today, "newCustomerCount");
        long anomaly = lng(today, "anomalyCount");
        return new MetricsView(
                day, store == null ? "ALL" : store, true,
                revenue, lng(today, "paidOrderCount"), arrival, newCust,
                lng(today, "refundCount"), lng(today, "refundFen"),
                lng(today, "contraYellowCount"), lng(today, "contraRedCount"), anomaly,
                prevRevenue, prevArrival, prevNew, prevAnomaly,
                pctChange(revenue, prevRevenue), pctChange(arrival, prevArrival),
                pctChange(newCust, prevNew), pctChange(anomaly, prevAnomaly));
    }

    private String composePrompt(String day, Map<String, Object> t, Map<String, Object> y) {
        long prevRevenue = y == null ? 0L : lng(y, "revenueFen");
        long prevArrival = y == null ? 0L : lng(y, "arrivalCount");
        return "你是医美连锁的经营分析经理，请仅基于以下 " + day + " 的真实经营数据生成门店经营日报。\n"
                + "【当日真实指标】已收款营收 " + yuan(lng(t, "revenueFen")) + " 元（"
                + lng(t, "paidOrderCount") + " 笔已收款订单）；到店登记 " + lng(t, "arrivalCount")
                + " 人次；首单新客 " + lng(t, "newCustomerCount") + " 人；已完成退款 "
                + lng(t, "refundCount") + " 笔、合计 " + yuan(lng(t, "refundFen")) + " 元；"
                + "收款风控黄单 " + lng(t, "contraYellowCount") + " 笔、红单 "
                + lng(t, "contraRedCount") + " 笔；异常项合计 " + lng(t, "anomalyCount") + "。\n"
                + "【上一自然日对比】上日营收 " + yuan(prevRevenue) + " 元、到店 " + prevArrival
                + " 人次。\n"
                + "请严格只输出一个 JSON 对象（不要 markdown、不要解释），字段如下：\n"
                + "{\"summary\":\"不超过 120 字的经营摘要，概括营收/到店/新客/异常的真实表现与环比\","
                + "\"suggestions\":[{\"type\":\"core 或 anomaly 或 action\","
                + "\"title\":\"不超过 30 字的标题\",\"detail\":\"不超过 100 字、必须由上述真实数据支撑的说明\"}]}\n"
                + "要求：suggestions 给出 3~5 条，其中 core（核心表现）1~2 条、anomaly（异常点，若无退款/红单可省略或写无异常）"
                + "0~2 条、action（可执行行动建议）1~2 条；只能使用给出的收款/到店/新客/退款/风控数据，"
                + "不得编造库存、客诉、差评、员工姓名、行为埋点等未提供的事实；不得输出疾病诊断与绝对化承诺，"
                + "符合 A1-17 隐私脱敏口径。";
    }

    /**
     * LLM 输出容错解析：截首个 { 到末个 }；summary 缺失/解析失败时用真实数值兜底；
     * suggestions 类型非法归 action、空数组时用真实指标规则补兜底建议（不抛 500，调用已真实计费落日志）。
     */
    private ParsedReport parse(String raw, Map<String, Object> t, Map<String, Object> y) {
        String summary = fallbackSummary(t, y);
        List<ParsedSug> sugs = new ArrayList<>();
        Set<String> seenTitles = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            String candidate = raw.trim();
            int start = candidate.indexOf('{');
            int end = candidate.lastIndexOf('}');
            if (start >= 0 && end > start) {
                candidate = candidate.substring(start, end + 1);
            }
            try {
                JsonNode node = json.readTree(candidate);
                String sm = node.path("summary").asText("").trim();
                if (!sm.isEmpty()) {
                    summary = truncate(sm, SUMMARY_MAX);
                }
                JsonNode arr = node.path("suggestions");
                if (arr.isArray()) {
                    for (JsonNode item : arr) {
                        String title = item.path("title").asText("").trim();
                        String detail = item.path("detail").asText("").trim();
                        if (title.isEmpty() || !seenTitles.add(title)) {
                            continue;
                        }
                        String type = normalizeType(item.path("type").asText("").trim());
                        sugs.add(new ParsedSug(type, title, detail.isEmpty() ? title : detail));
                        if (sugs.size() >= 5) {
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 非 JSON 输出：摘要与建议走真实数据兜底（不抛 500，调用本身已真实计费落日志）
            }
        }
        if (sugs.isEmpty()) {
            sugs = fallbackSuggestions(t, y);
        }
        return new ParsedReport(summary, sugs);
    }

    private String normalizeType(String raw) {
        return switch (raw) {
            case "core", "anomaly", "action" -> raw;
            default -> "action";
        };
    }

    /** 摘要兜底：直接用真实数值组织，不编造。 */
    private String fallbackSummary(Map<String, Object> t, Map<String, Object> y) {
        long prevRevenue = y == null ? 0L : lng(y, "revenueFen");
        Integer delta = pctChange(lng(t, "revenueFen"), prevRevenue);
        String trend = delta == null ? "上日无营收基数，环比不可算"
                : "营收环比 " + (delta >= 0 ? "+" : "") + delta + "%";
        return "当日已收款营收 " + yuan(lng(t, "revenueFen")) + " 元（" + lng(t, "paidOrderCount")
                + " 笔），到店 " + lng(t, "arrivalCount") + " 人次、新客 " + lng(t, "newCustomerCount")
                + " 人，异常项 " + lng(t, "anomalyCount") + "（退款 " + lng(t, "refundCount")
                + " 笔/风控红黄单 " + (lng(t, "contraYellowCount") + lng(t, "contraRedCount"))
                + " 笔）；" + trend + "。";
    }

    /** 建议兜底：按真实指标生成 2~3 条核心/异常/行动建议。 */
    private List<ParsedSug> fallbackSuggestions(Map<String, Object> t, Map<String, Object> y) {
        List<ParsedSug> out = new ArrayList<>();
        long anomaly = lng(t, "anomalyCount");
        long revenue = lng(t, "revenueFen");
        long prevRevenue = y == null ? 0L : lng(y, "revenueFen");
        Integer delta = pctChange(revenue, prevRevenue);
        String coreDetail = "已收款营收 " + yuan(revenue) + " 元、到店 " + lng(t, "arrivalCount")
                + " 人次、新客 " + lng(t, "newCustomerCount") + " 人（真实收款与到店登记聚合）";
        out.add(new ParsedSug("core", "核心指标：当日经营概览", coreDetail));
        if (anomaly > 0) {
            out.add(new ParsedSug("anomaly", "异常点：退款/风控单据需复核",
                    "当日已完成退款 " + lng(t, "refundCount") + " 笔、风控黄单 "
                            + lng(t, "contraYellowCount") + " 笔、红单 " + lng(t, "contraRedCount")
                            + " 笔，建议逐单复核原因"));
        }
        String actionTitle = delta != null && delta < 0 ? "行动建议：扭转营收下滑" : "行动建议：巩固到店转化";
        String actionDetail = delta == null
                ? "上日无营收基数，建议核对当日已收款 " + lng(t, "paidOrderCount") + " 笔订单的到店转化"
                : "营收环比 " + delta + "%，建议由当班顾问优先跟进当日到店未成交与首单新客复访";
        out.add(new ParsedSug("action", actionTitle, actionDetail));
        return out;
    }

    private ReportView toReportView(AiDailyReport r, List<AiDailySuggestion> sugs, boolean adoptedAll) {
        List<SuggestionView> views = new ArrayList<>();
        for (AiDailySuggestion s : sugs) {
            views.add(new SuggestionView(s.getSuggestionId(), s.getSuggestionType(), s.getTitle(),
                    s.getDetail(), Boolean.TRUE.equals(s.getAdopted()),
                    s.getAdoptedAt(), s.getAdoptedBy()));
        }
        return new ReportView(r.getReportId(), r.getReportDate(),
                r.getStoreCode() == null ? "" : r.getStoreCode(), r.getSummary(),
                r.getModelCode(), r.getInvokeLogId(), r.getTotalTokens(), r.getCostFen(),
                adoptedAll, views, r.getCreatedAt(), bjTime(r.getCreatedAt()));
    }

    /** timestamptz 经 JPA 以 UTC OffsetDateTime 返回，展示统一转 Asia/Shanghai 墙钟时间，避免差 8 小时。 */
    private String bjTime(OffsetDateTime t) {
        return t == null ? "" : t.withOffsetSameInstant(BJ).toLocalTime().format(TIME_FMT);
    }

    private String writeMetrics(Map<String, Object> today, Map<String, Object> y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("today", today);
        m.put("previousDay", y);
        try {
            String s = json.writeValueAsString(m);
            return s.length() > METRICS_MAX ? s.substring(0, METRICS_MAX) : s;
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 环比百分比：前期为 0 且本期 >0 → null（不可算，前端不显示趋势），双 0 → 0；结果夹 -999~9999。 */
    private Integer pctChange(long current, long previous) {
        if (previous == 0) {
            return current == 0 ? 0 : null;
        }
        int pct = (int) Math.round((current - previous) * 100.0 / previous);
        return Math.max(-999, Math.min(9999, pct));
    }

    private String resolveStore(String input, LoginUser user) {
        if (input != null && !input.isBlank() && !"ALL".equalsIgnoreCase(input.trim())) {
            return input.trim();
        }
        return user.storeCode() == null || user.storeCode().isBlank() ? null : user.storeCode();
    }

    private String normalizeDate(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now(BJ).format(DATE_FMT);
        }
        String d = date.trim();
        try {
            return LocalDate.parse(d).format(DATE_FMT);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "日期参数 date 格式非法，需 yyyy-MM-dd（如 2026-09-13）：" + d);
        }
    }

    private LoginUser requireUser() {
        LoginUser user = SecurityContext.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效，请重新登录");
        }
        return user;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static long lng(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o == null ? 0L : ((Number) o).longValue();
    }

    /** 分 → 元（四舍五入到元，供 prompt 文案）。 */
    private static String yuan(long fen) {
        return String.valueOf(Math.round(fen / 100.0));
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String payload(Map<String, ?> data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
