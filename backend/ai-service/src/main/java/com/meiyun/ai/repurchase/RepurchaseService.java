package com.meiyun.ai.repurchase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.client.CustomerProfileClient;
import com.meiyun.ai.client.TxnRepurchaseClient;
import com.meiyun.ai.domain.AiInvokeLogRepository;
import com.meiyun.ai.domain.AiRepurchasePrediction;
import com.meiyun.ai.domain.AiRepurchasePredictionRepository;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复购预测引擎（A1-03）：候选信号由 txn-service 经 X-Internal-Token 内部端点投影
 * （status='已收款' 的真实成交订单按客户聚合的 RFM/最近成交项目/平均客单价/平均成交间隔），
 * 客户名/掩码手机号/会员等级取自 customer-service，会员卡余额取自客户域卡余额投影；
 * 逐客户复用 repurchase 功能 invoke 全治理链（角色灰度矩阵 / 门店灰度 / 敏感词 / 配额 / 计费 / ai_invoke_log），
 * LLM 结构化输出（复购概率/推荐时机/预计转化）容错解析后按批次沉淀 ai_repurchase_prediction。
 *
 * <p>诚实口径：推荐项目为该客户最近一次真实成交项目（非模型编造）；预计转化为模型基于真实客单价与
 * 概率的估算（标注模型估算，非成交承诺）；「浏览/咨询行为活跃」暂无行为埋点数据源，推荐依据中如实标注
 * 不可得、不参与权重；无历史批次不编造「较上周 +N」趋势，前端据空态诚实引导；「建跟进/推送」为站内
 * 幂等登记，真实跟进任务 M3-08、营销推送 M5-03 下发为远期 Backlog。
 */
@Service
public class RepurchaseService {

    public static final String FEATURE_CODE = "repurchase";

    private static final ZoneOffset BJ = ZoneOffset.ofHours(8);
    private static final int RAW_MAX = 8000;
    private static final int SIGNALS_MAX = 4000;
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
    private static final int NAME_MAX = 32;
    private static final int PROJECT_MAX = 64;
    private static final int TIMING_MAX = 32;
    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 周期 → 预测窗口天数。 */
    private static final Map<String, Integer> HORIZON = Map.of("week", 7, "month", 30, "quarter", 90);

    /** 周期 → 推荐时机枚举（模型须在其中择一，兜底取首个）。 */
    private static final Map<String, List<String>> TIMING_OPTIONS = Map.of(
            "week", List.of("3天内", "本周", "1周内"),
            "month", List.of("2周内", "本月"),
            "quarter", List.of("本季"));

    /** 推荐依据权重基线（v1 基线配置，非实时推理产物）；rank2 暂无行为埋点，available=false 不参与权重。 */
    private static final List<FactorView> FACTOR_BASELINE = List.of(
            new FactorView(1, "历史项目周期吻合",
                    "该客户历史成交平均间隔与当前距上次消费天数比对（真实交易计算）", 0.38, true, ""),
            new FactorView(2, "浏览/咨询行为活跃",
                    "近 7 天项目浏览、客服会话提及次数", 0.0, false,
                    "暂无客户行为埋点数据源，本期不纳入评分，待行为采集系统建设后开启"),
            new FactorView(3, "会员卡余额充足",
                    "在用会员卡余额与推荐项目客单价比对（真实卡余额投影）", 0.19, true, ""));

    private final AiRepurchasePredictionRepository repo;
    private final AiInvokeLogRepository invokeLogRepo;
    private final FeatureInvokeService featureInvokeService;
    private final TxnRepurchaseClient txnClient;
    private final CustomerProfileClient customerClient;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public RepurchaseService(AiRepurchasePredictionRepository repo,
                             AiInvokeLogRepository invokeLogRepo,
                             FeatureInvokeService featureInvokeService,
                             TxnRepurchaseClient txnClient,
                             CustomerProfileClient customerClient,
                             AuditRecorder audit) {
        this.repo = repo;
        this.invokeLogRepo = invokeLogRepo;
        this.featureInvokeService = featureInvokeService;
        this.txnClient = txnClient;
        this.customerClient = customerClient;
        this.audit = audit;
    }

    // ============================ DTO ============================

    public record RunCmd(String period, String storeCode, Integer limit) {
    }

    public record RepurchaseView(Long predictionId, String customerId, String customerName, String phone,
                                 String level, String projectCode, String projectName, String timing,
                                 int prob, long expectedAmountFen, long avgTicketFen, Long recencyDays,
                                 Long avgIntervalDays, Long cardBalanceFen,
                                 boolean followupRegistered, boolean pushRegistered,
                                 Long invokeLogId, String modelCode, OffsetDateTime createdAt) {
    }

    public record BatchView(String batchNo, String period, int horizonDays, int size,
                            int avgProb, long expectedTotalFen,
                            long followupRegistered, String storeCode, OffsetDateTime createdAt) {
    }

    public record RepurchaseStats(long predictedCustomers, int avgProb, long expectedTotalFen,
                                  String expectedNote, long followupTotal, long weekInvokes,
                                  String trendNote, String modelVersion, boolean ran) {
    }

    public record FactorView(int rank, String title, String desc, double weight,
                             boolean available, String unavailableNote) {
    }

    public record FactorModelView(String modelVersion, String note, List<FactorView> rows) {
    }

    public record ActionResult(boolean changed, Long predictionId, String action) {
    }

    public record BatchFollowupResult(String batchNo, int affected, boolean changed) {
    }

    private record ParsedPrediction(int prob, String timing, long expectedAmountFen) {
    }

    // ============================ 业务方法 ============================

    /**
     * 运行预测：txn 候选 → 批量卡余额 + 逐客户客户域富化 → 逐客户 repurchase invoke → 解析落同批快照。
     * 刻意不加方法级事务（同 ProfileService），保证出站失败日志独立提交；落库用 saveAll 独立事务。
     */
    public BatchView run(RunCmd cmd) {
        LoginUser user = requireUser();
        String period = normalizePeriod(cmd == null ? null : cmd.period());
        int horizon = HORIZON.get(period);
        int limit = Math.min(Math.max(cmd == null || cmd.limit() == null ? DEFAULT_LIMIT : cmd.limit(), 1), MAX_LIMIT);
        String storeCode = cmd != null && cmd.storeCode() != null && !cmd.storeCode().isBlank()
                ? cmd.storeCode().trim() : user.storeCode();

        List<Map<String, Object>> candidates = txnClient.candidates(storeCode, limit);
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "暂无可预测的复购候选（交易服务未返回近期已收款客户），请先确认存在已收款订单后再运行预测");
        }

        Map<String, Long> cardBalance = loadCardBalances(storeCode);
        String batchNo = nextBatchNo();

        List<AiRepurchasePrediction> rows = new ArrayList<>();
        for (Map<String, Object> s : candidates) {
            String customerId = str(s.get("customerId"));
            if (customerId.isBlank()) {
                continue;
            }
            Map<String, Object> ctx = customerClient.fetchContext(customerId);
            String customerName = ctx == null ? "" : str(ctx.get("name"));
            if (customerName.isBlank()) {
                customerName = customerId;
            }
            String projectName = truncate(str(s.get("lastProject")), PROJECT_MAX);
            String projectCode = classifyProject(projectName);
            String rowStore = str(s.get("storeCode"));
            if (rowStore.isBlank()) {
                rowStore = storeCode == null ? "" : storeCode;
            }
            long avgTicket = asLong(s.get("avgTicketFen"));
            Long balance = cardBalance.get(customerId);

            FeatureInvokeService.InvokeView v = featureInvokeService.invoke(
                    FEATURE_CODE,
                    new FeatureInvokeService.InvokeCmd(
                            composePrompt(period, horizon, customerName, s, avgTicket, balance), rowStore));

            ParsedPrediction parsed = parse(v.content(), period, avgTicket);

            AiRepurchasePrediction p = new AiRepurchasePrediction();
            p.setBatchNo(batchNo);
            p.setCustomerId(customerId);
            p.setCustomerName(truncate(customerName, NAME_MAX));
            p.setStoreCode(rowStore);
            p.setPeriod(period);
            p.setHorizonDays(horizon);
            p.setProjectCode(projectCode);
            p.setProjectName(projectName);
            p.setProb(parsed.prob());
            p.setTiming(parsed.timing());
            p.setExpectedAmount(parsed.expectedAmountFen());
            p.setSignalsJson(write(buildSignals(s, balance)));
            p.setRawOutput(truncate(v.content() == null ? "" : v.content(), RAW_MAX));
            p.setInvokeLogId(v.logId());
            p.setModelCode(v.modelCode());
            p.setTotalTokens(v.totalTokens());
            p.setCostFen(v.costFen() == null ? 0L : v.costFen());
            p.setStaffId(user.staffId());
            p.setStaffName(user.staffName());
            rows.add(p);
        }

        List<AiRepurchasePrediction> saved = repo.saveAll(rows);
        audit.record("AI_REPURCHASE_PREDICTION", "BATCH-" + batchNo,
                DataScope.currentActor(), "RUN_PREDICTION",
                payload(Map.of("batchNo", batchNo, "period", period, "candidates", saved.size(),
                        "storeCode", storeCode == null ? "" : storeCode)));
        return toBatch(saved, storeCode);
    }

    /** 当前榜单：读选定周期最近批次，支持品类筛选；无批次 → 404 中文引导先运行。 */
    @Transactional(readOnly = true)
    public List<RepurchaseView> list(String period, String projectCode) {
        requireUser();
        String p = normalizePeriod(period);
        AiRepurchasePrediction any = repo.findFirstByPeriodOrderByPredictionIdDesc(p)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "本" + periodLabel(p) + "尚未运行复购预测，请先点击「运行预测」"));
        List<AiRepurchasePrediction> rows = (projectCode == null || projectCode.isBlank() || "all".equals(projectCode))
                ? repo.findByBatchNoOrderByProbDescPredictionIdAsc(any.getBatchNo())
                : repo.findByBatchNoAndProjectCodeOrderByProbDescPredictionIdAsc(any.getBatchNo(), projectCode.trim());
        List<RepurchaseView> out = new ArrayList<>();
        for (AiRepurchasePrediction r : rows) {
            Map<String, Object> ctx = customerClient.fetchContext(r.getCustomerId());
            Map<String, Long> sig = readSignals(r.getSignalsJson());
            out.add(new RepurchaseView(
                    r.getPredictionId(), r.getCustomerId(), r.getCustomerName(),
                    ctx == null ? "" : str(ctx.get("phone")),
                    ctx == null ? "" : str(ctx.get("level")),
                    r.getProjectCode(), r.getProjectName(), r.getTiming(),
                    r.getProb() == null ? 0 : r.getProb(),
                    r.getExpectedAmount() == null ? 0L : r.getExpectedAmount(),
                    sig.getOrDefault("avgTicketFen", 0L),
                    sig.get("recencyDays"), sig.get("avgIntervalDays"), sig.get("cardBalanceFen"),
                    Boolean.TRUE.equals(r.getFollowupRegistered()),
                    Boolean.TRUE.equals(r.getPushRegistered()),
                    r.getInvokeLogId(), r.getModelCode(), r.getCreatedAt()));
        }
        return out;
    }

    /** 当前批次元信息（标题/时间/规模）；无批次 404。 */
    @Transactional(readOnly = true)
    public BatchView currentBatch(String period) {
        requireUser();
        String p = normalizePeriod(period);
        AiRepurchasePrediction any = repo.findFirstByPeriodOrderByPredictionIdDesc(p)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "本" + periodLabel(p) + "尚未运行复购预测"));
        List<AiRepurchasePrediction> rows = repo.findByBatchNoOrderByProbDescPredictionIdAsc(any.getBatchNo());
        return toBatch(rows, any.getStoreCode());
    }

    /** 4 KPI：全部真实计数/聚合；无历史基线不造「较上周」趋势。 */
    @Transactional(readOnly = true)
    public RepurchaseStats stats(String period) {
        requireUser();
        String p = normalizePeriod(period);
        OffsetDateTime weekStart = OffsetDateTime.now(BJ).with(DayOfWeek.MONDAY)
                .toLocalDate().atStartOfDay().atOffset(BJ);
        long weekInvokes = invokeLogRepo.countByInvokedAtGreaterThanEqualAndFeatureCode(weekStart, FEATURE_CODE);

        AiRepurchasePrediction any = repo.findFirstByPeriodOrderByPredictionIdDesc(p).orElse(null);
        if (any == null) {
            return new RepurchaseStats(0, 0, 0L, "运行预测后按真实客单价×概率估算",
                    repo.countByFollowupRegisteredTrue(), weekInvokes,
                    "暂无历史批次，运行首季后展示环比趋势", "v1-2026-08", false);
        }
        List<AiRepurchasePrediction> rows = repo.findByBatchNoOrderByProbDescPredictionIdAsc(any.getBatchNo());
        long expected = 0;
        long probSum = 0;
        for (AiRepurchasePrediction r : rows) {
            expected += r.getExpectedAmount() == null ? 0L : r.getExpectedAmount();
            probSum += r.getProb() == null ? 0 : r.getProb();
        }
        int avg = rows.isEmpty() ? 0 : (int) Math.round(probSum * 1.0 / rows.size());
        return new RepurchaseStats(rows.size(), avg, expected,
                "模型基于真实客单价×复购概率估算，非成交承诺",
                repo.countByFollowupRegisteredTrue(), weekInvokes,
                "当前批次真实统计，环比趋势待历史批次积累", "v1-2026-08", true);
    }

    /** Top3 推荐依据：真实可算项与暂无数据源项如实区分。 */
    public FactorModelView factors() {
        requireUser();
        return new FactorModelView("v1-2026-08",
                "复购概率模型 v1 基线：周期吻合与卡余额为真实交易/卡数据信号，行为活跃待埋点建设；权重为先验贡献。",
                FACTOR_BASELINE);
    }

    /** 登记建跟进（站内幂等）；真实跟进任务 M3-08 下发为远期 Backlog。 */
    @Transactional
    public ActionResult registerFollowup(Long id) {
        return registerAction(id, true);
    }

    /** 登记推送（站内幂等）；真实营销推送 M5-03 下发为远期 Backlog。 */
    @Transactional
    public ActionResult registerPush(Long id) {
        return registerAction(id, false);
    }

    /** 当前批次批量登记建跟进（幂等：仅未登记行受影响）。 */
    @Transactional
    public BatchFollowupResult batchFollowup(String period) {
        LoginUser user = requireUser();
        String p = normalizePeriod(period);
        AiRepurchasePrediction any = repo.findFirstByPeriodOrderByPredictionIdDesc(p)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "本" + periodLabel(p) + "尚无预测批次，无法批量建跟进"));
        List<AiRepurchasePrediction> rows = repo.findByBatchNoOrderByProbDescPredictionIdAsc(any.getBatchNo());
        int affected = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (AiRepurchasePrediction r : rows) {
            if (!Boolean.TRUE.equals(r.getFollowupRegistered())) {
                r.setFollowupRegistered(true);
                r.setFollowupAt(now);
                r.setFollowupBy(user.staffId());
                affected++;
            }
        }
        if (affected > 0) {
            repo.saveAll(rows);
            audit.record("AI_REPURCHASE_PREDICTION", "BATCH-" + any.getBatchNo(),
                    DataScope.currentActor(), "BATCH_REGISTER_FOLLOWUP",
                    payload(Map.of("batchNo", any.getBatchNo(), "affected", affected)));
        }
        return new BatchFollowupResult(any.getBatchNo(), affected, affected > 0);
    }

    // ============================ 解析 / 组装 ============================

    private ActionResult registerAction(Long id, boolean followup) {
        LoginUser user = requireUser();
        AiRepurchasePrediction r = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "预测记录不存在（id=" + id + "）"));
        String action = followup ? "REGISTER_FOLLOWUP" : "REGISTER_PUSH";
        if (followup) {
            if (Boolean.TRUE.equals(r.getFollowupRegistered())) {
                return new ActionResult(false, id, "followup");
            }
            r.setFollowupRegistered(true);
            r.setFollowupAt(OffsetDateTime.now());
            r.setFollowupBy(user.staffId());
        } else {
            if (Boolean.TRUE.equals(r.getPushRegistered())) {
                return new ActionResult(false, id, "push");
            }
            r.setPushRegistered(true);
            r.setPushAt(OffsetDateTime.now());
            r.setPushBy(user.staffId());
        }
        repo.save(r);
        audit.record("AI_REPURCHASE_PREDICTION", "REPURCHASE-" + id,
                DataScope.currentActor(), action,
                payload(Map.of("customerId", r.getCustomerId(), "customerName", r.getCustomerName(),
                        "batchNo", r.getBatchNo())));
        return new ActionResult(true, id, followup ? "followup" : "push");
    }

    private String nextBatchNo() {
        String date = LocalDate.now(BJ).format(BATCH_FMT);
        String prefix = "RP" + date;
        long today = repo.countByBatchNoStartingWith(prefix);
        return prefix + String.format("%02d", today + 1);
    }

    /** 汇总在用会员卡余额（储值+赠送金，分）；非在用卡不计；客户域不可用 → 空 Map（信号降级，不阻断）。 */
    private Map<String, Long> loadCardBalances(String storeCode) {
        Map<String, Long> out = new HashMap<>();
        for (Map<String, Object> c : customerClient.cardBalances()) {
            if (!"在用".equals(str(c.get("status")))) {
                continue;
            }
            String cid = str(c.get("customerId"));
            if (cid.isBlank()) {
                continue;
            }
            out.merge(cid, asLong(c.get("balance")) + asLong(c.get("giftBalance")), Long::sum);
        }
        return out;
    }

    private Map<String, Object> buildSignals(Map<String, Object> s, Long cardBalanceFen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalOrders", asLong(s.get("totalOrders")));
        m.put("freq365", asLong(s.get("freq365")));
        m.put("orders90", asLong(s.get("orders90")));
        m.put("recencyDays", s.get("recencyDays") == null ? null : asLong(s.get("recencyDays")));
        m.put("avgIntervalDays", s.get("avgIntervalDays") == null ? null : asLong(s.get("avgIntervalDays")));
        m.put("avgTicketFen", asLong(s.get("avgTicketFen")));
        m.put("lastAmountFen", asLong(s.get("lastAmountFen")));
        m.put("rScore", asLong(s.get("rScore")));
        m.put("fScore", asLong(s.get("fScore")));
        m.put("mScore", asLong(s.get("mScore")));
        m.put("lifecycle", str(s.get("lifecycle")));
        m.put("segment", str(s.get("segment")));
        m.put("cardBalanceFen", cardBalanceFen);
        return m;
    }

    private String composePrompt(String period, int horizon, String customerName,
                                 Map<String, Object> s, long avgTicketFen, Long cardBalanceFen) {
        String interval = s.get("avgIntervalDays") == null ? "不足 2 次成交，暂无平均间隔"
                : asLong(s.get("avgIntervalDays")) + " 天";
        String balance = cardBalanceFen == null ? "无在用会员卡余额数据"
                : "在用会员卡余额约 " + yuan(cardBalanceFen) + " 元";
        return "你是医美连锁的客户复购预测分析师，请仅基于以下该客户的真实经营数据预测其复购概率。\n"
                + "【客户】" + customerName + "，生命周期：" + emptyTo(str(s.get("lifecycle")), "未知")
                + "，RFM 分群：" + emptyTo(str(s.get("segment")), "未知") + "。\n"
                + "【真实成交】累计已收款 " + asLong(s.get("totalOrders")) + " 单，近 365 天 "
                + asLong(s.get("freq365")) + " 单，近 90 天 " + asLong(s.get("orders90")) + " 单；"
                + "距上次成交 " + (s.get("recencyDays") == null ? "未知" : asLong(s.get("recencyDays")) + " 天")
                + "；历史平均成交间隔 " + interval + "。\n"
                + "【金额】近 365 天消费 " + trimDouble(s.get("monetary365Yuan")) + " 元，"
                + "平均客单价约 " + yuan(avgTicketFen) + " 元；最近一次成交项目："
                + emptyTo(str(s.get("lastProject")), "未登记") + "；" + balance + "。\n"
                + "【预测目标】未来 " + horizon + " 天（" + periodLabel(period) + "）对同类项目的复购概率。\n"
                + "请严格只输出一个 JSON 对象（不要 markdown、不要解释），字段如下：\n"
                + "{\"prob\":0到100的整数复购概率,"
                + "\"timing\":\"" + String.join("/", TIMING_OPTIONS.get(period)) + " 中择一的推荐时机\","
                + "\"expectedAmount\":预计本次复购转化金额整数（单位：分，参考真实客单价与概率估算）}\n"
                + "要求：概率必须由上述真实数据支撑，不得编造消费/到店/行为埋点；不得输出疾病诊断与绝对化承诺，"
                + "符合 A1-17 隐私脱敏口径。";
    }

    /**
     * LLM 输出容错解析：截取首个 { 到末个 }；prob 夹 0~100，timing 限定窗口枚举（不命中走周期兜底），
     * expectedAmount 非负（缺省按真实客单价×概率估算）。解析失败 → 概率 0、兜底时机、客单价概率估算额。
     */
    private ParsedPrediction parse(String raw, String period, long avgTicketFen) {
        int prob = 0;
        String timing = TIMING_OPTIONS.get(period).get(0);
        long expected = Math.round(avgTicketFen * prob / 100.0);
        if (raw != null && !raw.isBlank()) {
            String candidate = raw.trim();
            int start = candidate.indexOf('{');
            int end = candidate.lastIndexOf('}');
            if (start >= 0 && end > start) {
                candidate = candidate.substring(start, end + 1);
            }
            try {
                JsonNode node = json.readTree(candidate);
                prob = clamp(node.path("prob").asInt(0));
                String t = node.path("timing").asText("").trim();
                if (!t.isEmpty() && TIMING_OPTIONS.get(period).contains(t)) {
                    timing = truncate(t, TIMING_MAX);
                }
                long rawAmount = node.path("expectedAmount").asLong(-1L);
                expected = rawAmount < 0 ? Math.round(avgTicketFen * prob / 100.0) : rawAmount;
            } catch (Exception ignored) {
                // 非 JSON 输出：保留原文，结构字段走兜底（不抛 500，调用本身已真实计费落日志）
            }
        }
        return new ParsedPrediction(prob, timing, expected);
    }

    private Map<String, Long> readSignals(String raw) {
        Map<String, Long> out = new HashMap<>();
        try {
            JsonNode node = json.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            putLong(out, "avgTicketFen", node, "avgTicketFen");
            putLong(out, "recencyDays", node, "recencyDays");
            putLong(out, "avgIntervalDays", node, "avgIntervalDays");
            putLong(out, "cardBalanceFen", node, "cardBalanceFen");
        } catch (Exception ignored) {
            // 信号 JSON 损坏不影响榜单主字段
        }
        return out;
    }

    private void putLong(Map<String, Long> out, String key, JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v != null && !v.isNull()) {
            out.put(key, v.asLong(0L));
        }
    }

    /** 中文项目名规则归类品类 code。 */
    private String classifyProject(String name) {
        if (name == null || name.isBlank()) {
            return "other";
        }
        if (containsAny(name, "针", "注射", "玻尿酸", "肉毒", "水光", "填充", "瘦脸")) {
            return "inject";
        }
        if (containsAny(name, "抗衰", "紧致", "热玛吉", "提拉", "超声炮", "年轻化")) {
            return "anti";
        }
        if (containsAny(name, "身体", "塑形", "减脂", "纤体", "背部", "肩颈", "SPA", "spa")) {
            return "body";
        }
        if (containsAny(name, "皮肤", "嫩肤", "补水", "护理", "清洁", "补水", "祛斑", "祛痘", "美白", "面部")) {
            return "skin";
        }
        return "other";
    }

    private boolean containsAny(String name, String... keys) {
        for (String k : keys) {
            if (name.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private BatchView toBatch(List<AiRepurchasePrediction> rows, String storeCode) {
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "预测批次为空");
        }
        String batchNo = rows.get(0).getBatchNo();
        String period = rows.get(0).getPeriod();
        int horizon = rows.get(0).getHorizonDays() == null ? 7 : rows.get(0).getHorizonDays();
        long probSum = 0;
        long expected = 0;
        long followup = 0;
        for (AiRepurchasePrediction r : rows) {
            probSum += r.getProb() == null ? 0 : r.getProb();
            expected += r.getExpectedAmount() == null ? 0L : r.getExpectedAmount();
            if (Boolean.TRUE.equals(r.getFollowupRegistered())) {
                followup++;
            }
        }
        int avg = (int) Math.round(probSum * 1.0 / rows.size());
        return new BatchView(batchNo, period, horizon, rows.size(), avg, expected, followup,
                storeCode == null ? "" : storeCode, rows.get(0).getCreatedAt());
    }

    private String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return "week";
        }
        String p = period.trim();
        if (!HORIZON.containsKey(p)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预测周期仅支持 week/month/quarter");
        }
        return p;
    }

    private String periodLabel(String period) {
        return switch (period) {
            case "week" -> "周";
            case "month" -> "月";
            case "quarter" -> "季";
            default -> "周期";
        };
    }

    private LoginUser requireUser() {
        LoginUser user = SecurityContext.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效，请重新登录");
        }
        return user;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String emptyTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static long asLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static String trimDouble(Object o) {
        if (o == null) {
            return "0";
        }
        double d = ((Number) o).doubleValue();
        return String.valueOf(Math.round(d * 100) / 100.0);
    }

    /** 分 → 元（保留整数位显示）。 */
    private static String yuan(long fen) {
        return String.valueOf(Math.round(fen / 100.0));
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String write(Object data) {
        try {
            String s = json.writeValueAsString(data);
            return s.length() > SIGNALS_MAX ? s.substring(0, SIGNALS_MAX) : s;
        } catch (Exception e) {
            return "{}";
        }
    }

    private String payload(Map<String, ?> data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
