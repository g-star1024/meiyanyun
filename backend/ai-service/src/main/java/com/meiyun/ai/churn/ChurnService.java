package com.meiyun.ai.churn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.client.CustomerProfileClient;
import com.meiyun.ai.client.TxnChurnClient;
import com.meiyun.ai.domain.AiChurnPrediction;
import com.meiyun.ai.domain.AiChurnPredictionRepository;
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
import java.util.List;
import java.util.Map;

/**
 * 流失预警引擎（A1-09）：候选信号由 txn-service 经 X-Internal-Token 内部端点投影
 * （status='已收款' 的真实成交订单按客户聚合，<b>久未到店优先</b> 的 RFM/到店间隔/近 90 天消费下降率/
 * 最近成交日期），客户名/掩码手机号/会员等级取自 customer-service，会员卡余额取自客户域卡余额投影；
 * 逐客户复用 churn 功能 invoke 全治理链（角色灰度矩阵 / 门店灰度 / 敏感词 / 配额 / 计费 / ai_invoke_log），
 * LLM 结构化输出（流失风险分/关键因子/建议干预）容错解析后按批次沉淀 ai_churn_prediction。
 *
 * <p>诚实口径：关键因子必须由模型从入模真实信号中择一概括（不得编造客诉/差评/行为埋点等无数据源事实）；
 * 建议干预是给运营的建议而非已执行动作；模型 AUC/召回率暂无真实流失样本回流，不编造数值，KPI 中如实标注
 * 「v1 基线 · 待回流评估」；客诉差评、互动行为两因子暂无数据源，因子表 available=false 不参与评分；
 * 无历史批次不编造趋势，前端据空态诚实引导；干预为站内幂等登记，真实流失管理 M3-10、唤醒活动 M2-17、
 * 营销推送 M5-03 下发均为远期 Backlog。
 */
@Service
public class ChurnService {

    public static final String FEATURE_CODE = "churn";
    public static final String MODEL_VERSION = "v1-2026-09";

    private static final ZoneOffset BJ = ZoneOffset.ofHours(8);
    private static final int RAW_MAX = 8000;
    private static final int SIGNALS_MAX = 4000;
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
    private static final int NAME_MAX = 32;
    private static final int FACTOR_MAX = 64;
    private static final int ACTION_MAX = 64;
    private static final int DATE_MAX = 16;
    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 风险分阈值：>=85 高风险、>=60 中风险、其余低风险。 */
    private static final int HIGH_SCORE = 85;
    private static final int MID_SCORE = 60;

    /**
     * 流失因子重要性基线（v1 先验配置，非实时训练产物）。客诉差评、互动行为暂无数据源，
     * available=false 不参与评分、前端置灰；展示权重在可用因子间归一（到店间隔/消费下降/卡余额/会员等级）。
     */
    private static final List<FactorView> FACTOR_BASELINE = List.of(
            new FactorView(1, "最近一次到店间隔",
                    "距上次真实成交天数，间隔越长流失概率越高（真实交易计算）", 0.40, true, ""),
            new FactorView(2, "近 90 天消费下降率",
                    "近 90 天对比再前 90 天消费金额环比降幅（真实交易计算）", 0.30, true, ""),
            new FactorView(3, "客诉/差评次数",
                    "未闭环客诉与差评数量", 0.0, false,
                    "暂无客诉/差评业务数据源，本期不纳入评分，待客诉系统建设后开启"),
            new FactorView(4, "会员卡余额",
                    "在用会员卡余额，余额越低流失概率越高（真实卡余额投影）", 0.18, true, ""),
            new FactorView(5, "互动行为（开券/点击）",
                    "持续互动客户更稳定", 0.0, false,
                    "暂无客户行为埋点数据源，本期不纳入评分，待行为采集系统建设后开启"),
            new FactorView(6, "会员等级",
                    "高等级客户流失率更低（客户域真实等级）", 0.12, true, ""));

    private final AiChurnPredictionRepository repo;
    private final AiInvokeLogRepository invokeLogRepo;
    private final FeatureInvokeService featureInvokeService;
    private final TxnChurnClient txnClient;
    private final CustomerProfileClient customerClient;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public ChurnService(AiChurnPredictionRepository repo,
                        AiInvokeLogRepository invokeLogRepo,
                        FeatureInvokeService featureInvokeService,
                        TxnChurnClient txnClient,
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

    public record RunCmd(String storeCode, Integer limit) {
    }

    public record ChurnView(Long predictionId, String customerId, String customerName, String phone,
                            String level, String riskLevel, int score, String keyFactor,
                            String suggestedAction, String lastVisitDate, Long recencyDays,
                            Integer spendDeclinePct, Long cardBalanceFen,
                            boolean interveneRegistered, Long invokeLogId, String modelCode,
                            OffsetDateTime createdAt) {
    }

    public record BatchView(String batchNo, int size, int highCount, int midCount, int avgScore,
                            String storeCode, OffsetDateTime createdAt) {
    }

    public record ChurnStats(long scoredCustomers, long highCount, long midCount, long interveneTotal,
                             long weekInvokes, String modelVersion, String modelNote, boolean ran) {
    }

    public record FactorView(int rank, String title, String desc, double weight,
                             boolean available, String unavailableNote) {
    }

    public record FactorModelView(String modelVersion, String note, List<FactorView> rows) {
    }

    public record ActionResult(boolean changed, Long predictionId, String action) {
    }

    public record BatchInterveneResult(String batchNo, int affected, boolean changed) {
    }

    private record ParsedScore(int score, String keyFactor, String suggestedAction) {
    }

    // ============================ 业务方法 ============================

    /**
     * 运行评分：txn 久未到店候选 → 批量卡余额 + 逐客户客户域富化 → 逐客户 churn invoke → 解析落同批快照。
     * 刻意不加方法级事务（同 RepurchaseService），保证出站失败日志独立提交；落库用 saveAll 独立事务。
     */
    public BatchView run(RunCmd cmd) {
        LoginUser user = requireUser();
        int limit = Math.min(Math.max(cmd == null || cmd.limit() == null ? DEFAULT_LIMIT : cmd.limit(), 1), MAX_LIMIT);
        String storeCode = cmd != null && cmd.storeCode() != null && !cmd.storeCode().isBlank()
                ? cmd.storeCode().trim() : user.storeCode();

        List<Map<String, Object>> candidates = txnClient.candidates(storeCode, limit);
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "暂无可评分的流失候选（交易服务未返回已收款客户），请先确认存在已收款订单后再运行评分");
        }

        Map<String, Long> cardBalance = loadCardBalances();
        String batchNo = nextBatchNo();

        List<AiChurnPrediction> rows = new ArrayList<>();
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
            String rowStore = str(s.get("storeCode"));
            if (rowStore.isBlank()) {
                rowStore = storeCode == null ? "" : storeCode;
            }
            String level = ctx == null ? "" : str(ctx.get("level"));
            Long balance = cardBalance.get(customerId);

            FeatureInvokeService.InvokeView v = featureInvokeService.invoke(
                    FEATURE_CODE,
                    new FeatureInvokeService.InvokeCmd(composePrompt(customerName, level, s, balance), rowStore));

            ParsedScore parsed = parse(v.content(), s);

            AiChurnPrediction p = new AiChurnPrediction();
            p.setBatchNo(batchNo);
            p.setCustomerId(customerId);
            p.setCustomerName(truncate(customerName, NAME_MAX));
            p.setStoreCode(rowStore);
            p.setScore(parsed.score());
            p.setRiskLevel(levelOf(parsed.score()));
            p.setKeyFactor(parsed.keyFactor());
            p.setSuggestedAction(parsed.suggestedAction());
            p.setLastVisitDate(truncate(str(s.get("lastVisitDate")), DATE_MAX));
            p.setRecencyDays(asLong(s.get("recencyDays")));
            p.setSpendDeclinePct(s.get("spendDeclinePct") == null ? null
                    : (int) Math.max(0, Math.min(100, asLong(s.get("spendDeclinePct")))));
            p.setSignalsJson(write(buildSignals(s, level, balance)));
            p.setRawOutput(truncate(v.content() == null ? "" : v.content(), RAW_MAX));
            p.setInvokeLogId(v.logId());
            p.setModelCode(v.modelCode());
            p.setTotalTokens(v.totalTokens());
            p.setCostFen(v.costFen() == null ? 0L : v.costFen());
            p.setStaffId(user.staffId());
            p.setStaffName(user.staffName());
            rows.add(p);
        }

        List<AiChurnPrediction> saved = repo.saveAll(rows);
        audit.record("AI_CHURN_PREDICTION", "BATCH-" + batchNo,
                DataScope.currentActor(), "RUN_SCORING",
                payload(Map.of("batchNo", batchNo, "candidates", saved.size(),
                        "storeCode", storeCode == null ? "" : storeCode)));
        return toBatch(saved, storeCode);
    }

    /** 当前风险榜：读最近批次，支持风险等级筛选；无批次 → 404 中文引导先运行。 */
    @Transactional(readOnly = true)
    public List<ChurnView> list(String riskLevel) {
        requireUser();
        AiChurnPrediction any = repo.findFirstByOrderByPredictionIdDesc()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "尚未运行流失评分，请先点击「运行评分」"));
        boolean filter = riskLevel != null && !riskLevel.isBlank() && !"all".equals(riskLevel);
        List<AiChurnPrediction> rows = filter
                ? repo.findByBatchNoAndRiskLevelOrderByScoreDescPredictionIdAsc(any.getBatchNo(), riskLevel.trim())
                : repo.findByBatchNoOrderByScoreDescPredictionIdAsc(any.getBatchNo());
        List<ChurnView> out = new ArrayList<>();
        for (AiChurnPrediction r : rows) {
            Map<String, Object> ctx = customerClient.fetchContext(r.getCustomerId());
            Map<String, Long> sig = readSignals(r.getSignalsJson());
            out.add(new ChurnView(
                    r.getPredictionId(), r.getCustomerId(), r.getCustomerName(),
                    ctx == null ? "" : str(ctx.get("phone")),
                    ctx == null ? "" : str(ctx.get("level")),
                    r.getRiskLevel(), r.getScore() == null ? 0 : r.getScore(),
                    r.getKeyFactor(), r.getSuggestedAction(), r.getLastVisitDate(),
                    r.getRecencyDays(), r.getSpendDeclinePct(), sig.get("cardBalanceFen"),
                    Boolean.TRUE.equals(r.getInterveneRegistered()),
                    r.getInvokeLogId(), r.getModelCode(), r.getCreatedAt()));
        }
        return out;
    }

    /** 当前批次元信息（标题/时间/规模/高/中计数）；无批次 404。 */
    @Transactional(readOnly = true)
    public BatchView currentBatch() {
        requireUser();
        AiChurnPrediction any = repo.findFirstByOrderByPredictionIdDesc()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "尚未运行流失评分"));
        return toBatch(repo.findByBatchNoOrderByScoreDescPredictionIdAsc(any.getBatchNo()), any.getStoreCode());
    }

    /** KPI：高/中风险与已干预全部真实计数；AUC/召回率无真实回流不造数，modelNote 诚实标注待评估。 */
    @Transactional(readOnly = true)
    public ChurnStats stats() {
        requireUser();
        OffsetDateTime weekStart = OffsetDateTime.now(BJ).with(DayOfWeek.MONDAY)
                .toLocalDate().atStartOfDay().atOffset(BJ);
        long weekInvokes = invokeLogRepo.countByInvokedAtGreaterThanEqualAndFeatureCode(weekStart, FEATURE_CODE);

        AiChurnPrediction any = repo.findFirstByOrderByPredictionIdDesc().orElse(null);
        if (any == null) {
            return new ChurnStats(0, 0, 0, repo.countByInterveneRegisteredTrue(), weekInvokes,
                    MODEL_VERSION, "v1 规则+模型基线，AUC/召回率待真实流失样本回流后评估", false);
        }
        String batchNo = any.getBatchNo();
        List<AiChurnPrediction> rows = repo.findByBatchNoOrderByScoreDescPredictionIdAsc(batchNo);
        return new ChurnStats(rows.size(),
                repo.countByBatchNoAndRiskLevel(batchNo, "high"),
                repo.countByBatchNoAndRiskLevel(batchNo, "mid"),
                repo.countByInterveneRegisteredTrue(), weekInvokes,
                MODEL_VERSION, "当前批次真实统计；AUC/召回率待真实流失样本回流后评估", true);
    }

    /** 流失因子：真实可算项与暂无数据源项如实区分（置灰项 weight=0 不参与评分）。 */
    public FactorModelView factors() {
        requireUser();
        return new FactorModelView(MODEL_VERSION,
                "流失风险模型 v1 基线：到店间隔/消费下降率/卡余额/会员等级为真实交易与客户数据信号，"
                        + "客诉差评与互动行为待数据源建设；权重为先验贡献，模型效果待流失样本回流评估。",
                FACTOR_BASELINE);
    }

    /** 登记干预（站内幂等）；真实流失管理 M3-10/唤醒 M2-17/推送 M5-03 下发为远期 Backlog。 */
    @Transactional
    public ActionResult registerIntervene(Long id) {
        LoginUser user = requireUser();
        AiChurnPrediction r = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "评分记录不存在（id=" + id + "）"));
        if (Boolean.TRUE.equals(r.getInterveneRegistered())) {
            return new ActionResult(false, id, "intervene");
        }
        r.setInterveneRegistered(true);
        r.setInterveneAt(OffsetDateTime.now());
        r.setInterveneBy(user.staffId());
        repo.save(r);
        audit.record("AI_CHURN_PREDICTION", "CHURN-" + id,
                DataScope.currentActor(), "REGISTER_INTERVENE",
                payload(Map.of("customerId", r.getCustomerId(), "customerName", r.getCustomerName(),
                        "batchNo", r.getBatchNo(), "score", r.getScore(), "riskLevel", r.getRiskLevel())));
        return new ActionResult(true, id, "intervene");
    }

    /** 当前批次批量登记干预（幂等：仅未登记行受影响）。 */
    @Transactional
    public BatchInterveneResult batchIntervene() {
        LoginUser user = requireUser();
        AiChurnPrediction any = repo.findFirstByOrderByPredictionIdDesc()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "尚无评分批次，无法批量登记干预"));
        List<AiChurnPrediction> rows = repo.findByBatchNoOrderByScoreDescPredictionIdAsc(any.getBatchNo());
        int affected = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (AiChurnPrediction r : rows) {
            if (!Boolean.TRUE.equals(r.getInterveneRegistered())) {
                r.setInterveneRegistered(true);
                r.setInterveneAt(now);
                r.setInterveneBy(user.staffId());
                affected++;
            }
        }
        if (affected > 0) {
            repo.saveAll(rows);
            audit.record("AI_CHURN_PREDICTION", "BATCH-" + any.getBatchNo(),
                    DataScope.currentActor(), "BATCH_REGISTER_INTERVENE",
                    payload(Map.of("batchNo", any.getBatchNo(), "affected", affected)));
        }
        return new BatchInterveneResult(any.getBatchNo(), affected, affected > 0);
    }

    // ============================ 解析 / 组装 ============================

    private String nextBatchNo() {
        String date = LocalDate.now(BJ).format(BATCH_FMT);
        String prefix = "CH" + date;
        long today = repo.countByBatchNoStartingWith(prefix);
        return prefix + String.format("%02d", today + 1);
    }

    /** 汇总在用会员卡余额（储值+赠送金，分）；非在用卡不计；客户域不可用 → 空 Map（信号降级，不阻断）。 */
    private Map<String, Long> loadCardBalances() {
        Map<String, Long> out = new LinkedHashMap<>();
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

    private Map<String, Object> buildSignals(Map<String, Object> s, String level, Long cardBalanceFen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalOrders", asLong(s.get("totalOrders")));
        m.put("freq365", asLong(s.get("freq365")));
        m.put("orders90", asLong(s.get("orders90")));
        m.put("recencyDays", s.get("recencyDays") == null ? null : asLong(s.get("recencyDays")));
        m.put("monetary365Yuan", trimDouble(s.get("monetary365Yuan")));
        m.put("spend90Fen", asLong(s.get("spend90Fen")));
        m.put("spendPrev90Fen", asLong(s.get("spendPrev90Fen")));
        m.put("spendDeclinePct", s.get("spendDeclinePct") == null ? null : asLong(s.get("spendDeclinePct")));
        m.put("rScore", asLong(s.get("rScore")));
        m.put("fScore", asLong(s.get("fScore")));
        m.put("mScore", asLong(s.get("mScore")));
        m.put("lifecycle", str(s.get("lifecycle")));
        m.put("segment", str(s.get("segment")));
        m.put("lastProject", str(s.get("lastProject")));
        m.put("level", level);
        m.put("cardBalanceFen", cardBalanceFen);
        return m;
    }

    private String composePrompt(String customerName, String level,
                                 Map<String, Object> s, Long cardBalanceFen) {
        String decline = s.get("spendDeclinePct") == null
                ? "再前 90 天无成交基数，下降率不可算"
                : "近 90 天消费较再前 90 天下降约 " + asLong(s.get("spendDeclinePct")) + "%";
        String balance = cardBalanceFen == null ? "无在用会员卡余额数据"
                : "在用会员卡余额约 " + yuan(cardBalanceFen) + " 元";
        return "你是医美连锁的客户流失预警分析师，请仅基于以下该客户的真实经营数据评估其未来 90 天流失风险。\n"
                + "【客户】" + customerName + "，会员等级：" + emptyTo(level, "未知")
                + "，生命周期：" + emptyTo(str(s.get("lifecycle")), "未知")
                + "，RFM 分群：" + emptyTo(str(s.get("segment")), "未知") + "。\n"
                + "【真实到店】累计已收款 " + asLong(s.get("totalOrders")) + " 单，近 365 天 "
                + asLong(s.get("freq365")) + " 单，近 90 天 " + asLong(s.get("orders90")) + " 单；"
                + "距上次成交 " + (s.get("recencyDays") == null ? "未知" : asLong(s.get("recencyDays")) + " 天")
                + "（最近到店 " + emptyTo(str(s.get("lastVisitDate")), "未登记") + "）。\n"
                + "【金额】近 365 天消费 " + trimDouble(s.get("monetary365Yuan")) + " 元；" + decline + "；"
                + balance + "；最近一次成交项目：" + emptyTo(str(s.get("lastProject")), "未登记") + "。\n"
                + "【评分目标】输出该客户流失风险分（0~100，分数越高未来 90 天流失可能性越大）。\n"
                + "请严格只输出一个 JSON 对象（不要 markdown、不要解释），字段如下：\n"
                + "{\"score\":0到100的整数流失风险分,"
                + "\"keyFactor\":\"从上面真实信号中择一概括的最关键流失因子（如：60 天未到店 / 消费金额骤降 / "
                + "会员卡余额低 / 到店频次下降，不得编造客诉、差评、开券点击等未提供的数据）\","
                + "\"suggestedAction\":\"不超过 20 字的具体干预建议（如：专属顾问外呼+唤醒券 / 疗程跟进提醒）\"}\n"
                + "要求：分数必须由上述真实数据支撑，不得编造消费/到店/客诉/行为埋点；不得输出疾病诊断与绝对化承诺，"
                + "符合 A1-17 隐私脱敏口径。";
    }

    /**
     * LLM 输出容错解析：截取首个 { 到末个 }；score 夹 0~100；keyFactor/suggestedAction 截断。
     * 解析失败 → 分数 0、关键因子按真实 recency 兜底（不抛 500，调用本身已真实计费落日志）。
     */
    private ParsedScore parse(String raw, Map<String, Object> s) {
        int score = 0;
        String keyFactor = fallbackFactor(s);
        String action = "人工复核后制定干预方案";
        if (raw != null && !raw.isBlank()) {
            String candidate = raw.trim();
            int start = candidate.indexOf('{');
            int end = candidate.lastIndexOf('}');
            if (start >= 0 && end > start) {
                candidate = candidate.substring(start, end + 1);
            }
            try {
                JsonNode node = json.readTree(candidate);
                score = clamp(node.path("score").asInt(0));
                String kf = node.path("keyFactor").asText("").trim();
                if (!kf.isEmpty()) {
                    keyFactor = truncate(kf, FACTOR_MAX);
                }
                String ac = node.path("suggestedAction").asText("").trim();
                if (!ac.isEmpty()) {
                    action = truncate(ac, ACTION_MAX);
                }
            } catch (Exception ignored) {
                // 非 JSON 输出：保留原文，结构字段走兜底（不抛 500，调用本身已真实计费落日志）
            }
        }
        return new ParsedScore(score, keyFactor, action);
    }

    /** 模型未给出关键因子时，按真实信号生成兜底因子（不编造无数据源事实）。 */
    private String fallbackFactor(Map<String, Object> s) {
        long recency = asLong(s.get("recencyDays"));
        long decline = asLong(s.get("spendDeclinePct"));
        if (decline >= 40) {
            return "消费金额骤降约" + decline + "%";
        }
        if (recency >= 180) {
            return recency + " 天未到店";
        }
        if (recency >= 90) {
            return recency + " 天未到店";
        }
        if (asLong(s.get("orders90")) == 0) {
            return "近 90 天无成交";
        }
        return "到店频次下降";
    }

    private Map<String, Long> readSignals(String raw) {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            JsonNode node = json.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            JsonNode v = node.get("cardBalanceFen");
            if (v != null && !v.isNull()) {
                out.put("cardBalanceFen", v.asLong(0L));
            }
        } catch (Exception ignored) {
            // 信号 JSON 损坏不影响榜单主字段
        }
        return out;
    }

    private String levelOf(int score) {
        if (score >= HIGH_SCORE) {
            return "high";
        }
        if (score >= MID_SCORE) {
            return "mid";
        }
        return "low";
    }

    private BatchView toBatch(List<AiChurnPrediction> rows, String storeCode) {
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "评分批次为空");
        }
        long high = 0;
        long mid = 0;
        long scoreSum = 0;
        for (AiChurnPrediction r : rows) {
            int sc = r.getScore() == null ? 0 : r.getScore();
            scoreSum += sc;
            if (sc >= HIGH_SCORE) {
                high++;
            } else if (sc >= MID_SCORE) {
                mid++;
            }
        }
        int avg = (int) Math.round(scoreSum * 1.0 / rows.size());
        return new BatchView(rows.get(0).getBatchNo(), rows.size(), (int) high, (int) mid, avg,
                storeCode == null ? "" : storeCode, rows.get(0).getCreatedAt());
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
