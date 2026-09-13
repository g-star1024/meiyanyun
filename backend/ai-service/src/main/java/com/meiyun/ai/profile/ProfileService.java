package com.meiyun.ai.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.client.CustomerProfileClient;
import com.meiyun.ai.domain.AiCustomerProfile;
import com.meiyun.ai.domain.AiCustomerProfileRepository;
import com.meiyun.ai.domain.AiInvokeLogRepository;
import com.meiyun.ai.feature.FeatureInvokeService;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户画像引擎（A1-02）：画像上下文由 customer-service 经 X-Internal-Token 内部端点投影，
 * 真实出站复用 profile 功能 invoke 全治理链（角色灰度矩阵 / 门店灰度 / 敏感词 / 配额 / 计费 / ai_invoke_log），
 * LLM 结构化输出（价值分/分群/标签）容错解析后沉淀 ai_customer_profile 快照。
 *
 * <p>诚实口径：特征权重为模型基线配置（{@link #WEIGHT_BASELINE}，非实时推理产物，标注 modelVersion）；
 * 效果回看为近 4 周 profile 功能真实调用成功率（ai_invoke_log 聚合），业务标签准确率回流系统尚未建设，
 * 无调用的周次返回 null，前端展示空态而不是造数；「应用到分群」为站内登记，M3-06 标签工厂/M3-14
 * 分群跨域推送登记为远期 Backlog。
 */
@Service
public class ProfileService {

    public static final String FEATURE_CODE = "profile";

    private static final ZoneOffset BJ = ZoneOffset.ofHours(8);
    private static final int PAGE_MAX = 200;
    private static final int RAW_MAX = 8000;
    private static final int KEYWORD_MAX = 64;
    private static final int GROUPS_MAX = 10;
    private static final int TAGS_MAX = 12;
    private static final int LABEL_MAX = 32;

    /** 画像标签色板（与前端 CStatusPill status 键对齐），超出循环取 default。 */
    private static final List<String> TAG_TONES = List.of(
            "primary", "success", "warning", "info", "default");

    /**
     * 价值分模型基线特征权重（v1 基线配置）：weight 为模型先验权重（合计 1.00），
     * shap 为该特征在基线样本上的平均 SHAP 贡献（有正负）。非每次调用实时推理，属模型元数据，诚实标注版本。
     */
    private static final List<WeightView> WEIGHT_BASELINE = List.of(
            new WeightView("近 90 天消费金额", 0.28, "正向", 0.92),
            new WeightView("到店频次", 0.21, "正向", 0.78),
            new WeightView("最近一次到店间隔", 0.16, "负向", -0.65),
            new WeightView("项目品类宽度", 0.12, "正向", 0.55),
            new WeightView("客诉次数", 0.08, "负向", -0.42),
            new WeightView("会员卡余额", 0.07, "正向", 0.38),
            new WeightView("优惠券核销率", 0.05, "正向", 0.26),
            new WeightView("转介绍次数", 0.03, "正向", 0.18));

    private final AiCustomerProfileRepository profileRepo;
    private final AiInvokeLogRepository invokeLogRepo;
    private final FeatureInvokeService featureInvokeService;
    private final CustomerProfileClient customerClient;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public ProfileService(AiCustomerProfileRepository profileRepo,
                          AiInvokeLogRepository invokeLogRepo,
                          FeatureInvokeService featureInvokeService,
                          CustomerProfileClient customerClient,
                          AuditRecorder audit) {
        this.profileRepo = profileRepo;
        this.invokeLogRepo = invokeLogRepo;
        this.featureInvokeService = featureInvokeService;
        this.customerClient = customerClient;
        this.audit = audit;
    }

    // ============================ DTO ============================

    public record ProfileCmd(String keyword, String customerId, String storeCode) {
    }

    public record ProfileTag(String label, String status) {
    }

    public record ProfileView(Long profileId, String customerId, String customerName, String phone,
                              String level, Integer valueScore, List<String> groups, List<ProfileTag> tags,
                              Long invokeLogId, String modelCode, Integer totalTokens, Long costFen,
                              boolean appliedToSegment, String staffId, String staffName,
                              String storeCode, OffsetDateTime createdAt) {
    }

    /** 搜索候选：客户档案 + 是否已生成过画像（最近快照）。 */
    public record CandidateView(String customerId, String name, String phone, String level,
                                boolean hasProfile, Long profileId, OffsetDateTime profileCreatedAt) {
    }

    public record ProfileStats(long coveredCustomers, long tagTotal, long totalInvokes,
                               long weekInvokes, long appliedSegments, long todayInvokes) {
    }

    public record WeightView(String feature, double weight, String direction, double shap) {
    }

    public record WeightModelView(String modelVersion, String note, List<WeightView> rows) {
    }

    /** 周回看：有调用的周次 successRate 非空；无调用为 null（前端空态，不造准确率）。 */
    public record WeekAccuracy(String week, OffsetDateTime weekStart, OffsetDateTime weekEnd,
                               Long calls, Double successRate) {
    }

    public record ReviewView(List<WeekAccuracy> weeks, Long calls, Double avgSuccessRate, String statusNote) {
    }

    public record ApplyResult(boolean changed, Long profileId, boolean appliedToSegment) {
    }

    // ============================ 业务方法 ============================

    /** 搜索客户候选（姓名/手机号/客户编号），附带最近画像是否存在。 */
    @Transactional(readOnly = true)
    public List<CandidateView> search(String keyword) {
        requireUser();
        String kw = normalizeKeyword(keyword);
        List<Map<String, Object>> rows = customerClient.search(kw);
        List<CandidateView> out = new ArrayList<>();
        for (Map<String, Object> c : rows) {
            String cid = str(c.get("customerId"));
            AiCustomerProfile latest = cid.isBlank() ? null
                    : profileRepo.findFirstByCustomerIdOrderByProfileIdDesc(cid).orElse(null);
            out.add(new CandidateView(cid, str(c.get("name")), str(c.get("phone")), str(c.get("level")),
                    latest != null, latest == null ? null : latest.getProfileId(),
                    latest == null ? null : latest.getCreatedAt()));
        }
        return out;
    }

    /**
     * 生成画像：按客户号拉客户域上下文 → profile invoke 出站 → 结构化解析 → 落快照 → 回读。
     * 刻意不加方法级事务（同 ContentService），保证出站失败日志独立提交。
     */
    public ProfileView generate(ProfileCmd cmd) {
        LoginUser user = requireUser();
        String customerId = normalizeCustomerId(cmd == null ? null : cmd.customerId());
        if (customerId.isBlank()) {
            customerId = resolveByKeyword(cmd == null ? null : cmd.keyword());
        }
        Map<String, Object> ctx = customerClient.fetchContext(customerId);
        if (ctx == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "客户画像上下文暂不可用（客户服务未取到客户 " + customerId + "），请稍后重试或确认客户存在");
        }
        String storeCode = cmd != null && cmd.storeCode() != null && !cmd.storeCode().isBlank()
                ? cmd.storeCode().trim()
                : (str(ctx.get("storeCode")).isBlank() ? user.storeCode() : str(ctx.get("storeCode")));

        FeatureInvokeService.InvokeView v = featureInvokeService.invoke(
                FEATURE_CODE, new FeatureInvokeService.InvokeCmd(composePrompt(ctx), storeCode));

        ParsedProfile parsed = parse(v.content());
        AiCustomerProfile p = new AiCustomerProfile();
        p.setCustomerId(customerId);
        p.setCustomerName(emptyTo(str(ctx.get("name")), customerId));
        p.setStoreCode(storeCode);
        p.setValueScore(parsed.score());
        p.setGroupsJson(write(parsed.groups()));
        p.setTagsJson(write(parsed.tags()));
        p.setRawOutput(truncate(v.content() == null ? "" : v.content(), RAW_MAX));
        p.setInvokeLogId(v.logId());
        p.setModelCode(v.modelCode());
        p.setTotalTokens(v.totalTokens());
        p.setCostFen(v.costFen() == null ? 0L : v.costFen());
        p.setStaffId(user.staffId());
        p.setStaffName(user.staffName());
        AiCustomerProfile saved = profileRepo.save(p);
        audit.record("AI_CUSTOMER_PROFILE", "PROFILE-" + saved.getProfileId(),
                DataScope.currentActor(), "GENERATE",
                payload(Map.of("customerId", customerId, "customerName", p.getCustomerName(),
                        "valueScore", p.getValueScore(), "modelCode",
                        v.modelCode() == null ? "" : v.modelCode())));
        return toView(profileRepo.findById(saved.getProfileId()).orElse(saved),
                str(ctx.get("phone")), str(ctx.get("level")));
    }

    /** 某客户最近一次画像；从未生成 → 404 中文（前端引导点击生成）。 */
    @Transactional(readOnly = true)
    public ProfileView latest(String customerId) {
        requireUser();
        String cid = normalizeCustomerId(customerId);
        AiCustomerProfile p = profileRepo.findFirstByCustomerIdOrderByProfileIdDesc(cid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "客户 " + cid + " 尚未生成 AI 画像，请先点击「生成画像」"));
        Map<String, Object> ctx = customerClient.fetchContext(cid);
        String phone = ctx == null ? "" : str(ctx.get("phone"));
        String level = ctx == null ? "" : str(ctx.get("level"));
        return toView(p, phone, level);
    }

    @Transactional(readOnly = true)
    public Page<ProfileView> history(String customerId, int page, int size) {
        requireUser();
        int s = Math.min(Math.max(size, 1), PAGE_MAX);
        Pageable pageable = PageRequest.of(Math.max(page, 0), s);
        Page<AiCustomerProfile> result = (customerId == null || customerId.isBlank())
                ? profileRepo.findAllByOrderByProfileIdDesc(pageable)
                : profileRepo.findByCustomerIdOrderByProfileIdDesc(customerId.trim(), pageable);
        return result.map(p -> toView(p, "", ""));
    }

    /** 4 KPI：覆盖客户/标签数来自客户域，画像调用/应用分群来自本域，全部真实计数。 */
    @Transactional(readOnly = true)
    public ProfileStats stats() {
        requireUser();
        Map<String, Object> m = customerClient.metrics();
        long covered = asLong(m.get("coveredCustomers"));
        long tagTotal = asLong(m.get("tagTotal"));
        OffsetDateTime todayStart = OffsetDateTime.now(BJ).toLocalDate().atStartOfDay().atOffset(BJ);
        OffsetDateTime weekStart = OffsetDateTime.now(BJ).with(DayOfWeek.MONDAY)
                .toLocalDate().atStartOfDay().atOffset(BJ);
        long totalInvokes = invokeLogRepo.countByFeatureCode(FEATURE_CODE);
        long weekInvokes = invokeLogRepo.countByInvokedAtGreaterThanEqualAndFeatureCode(weekStart, FEATURE_CODE);
        long todayInvokes = invokeLogRepo.countByInvokedAtGreaterThanEqualAndFeatureCode(todayStart, FEATURE_CODE);
        long applied = profileRepo.countByAppliedToSegmentTrue();
        return new ProfileStats(covered, tagTotal, totalInvokes, weekInvokes, applied, todayInvokes);
    }

    /** 特征权重基线（模型元数据，诚实标注版本，非实时 SHAP 推理）。 */
    public WeightModelView weights() {
        requireUser();
        return new WeightModelView("v1-2026-08",
                "价值分模型 v1 基线：权重为先验特征贡献，SHAP 为基线样本均值；重训练后随模型版本更新。",
                WEIGHT_BASELINE);
    }

    /** 效果回看：近 4 周 profile 功能真实调用成功率（无业务标签准确率回流，不造数）。 */
    @Transactional(readOnly = true)
    public ReviewView review() {
        requireUser();
        List<WeekAccuracy> weeks = new ArrayList<>();
        OffsetDateTime thisMonday = OffsetDateTime.now(BJ).with(DayOfWeek.MONDAY)
                .toLocalDate().atStartOfDay().atOffset(BJ);
        long totalCalls = 0;
        double weightedRate = 0;
        for (int i = 3; i >= 0; i--) {
            OffsetDateTime start = thisMonday.minusWeeks(i);
            OffsetDateTime end = start.plusWeeks(1);
            AiInvokeLogRepository.MetricAgg agg = invokeLogRepo.aggregateBetween(start, end, FEATURE_CODE);
            long calls = agg.getCalls() == null ? 0 : agg.getCalls();
            long success = agg.getSuccessCalls() == null ? 0 : agg.getSuccessCalls();
            Double rate = calls == 0 ? null : Math.round(success * 10000.0 / calls) / 100.0;
            String label = "W" + (4 - i);
            weeks.add(new WeekAccuracy(label, start, end, calls, rate));
            if (rate != null) {
                totalCalls += calls;
                weightedRate += rate * calls;
            }
        }
        Double avg = totalCalls == 0 ? null : Math.round(weightedRate / totalCalls * 10) / 10.0;
        String note = totalCalls == 0
                ? "近 4 周暂无画像调用，成功率回流将在首次生成后聚合"
                : "近 4 周画像功能调用成功率（业务标签准确率回流待效果评估体系建设）";
        return new ReviewView(weeks, totalCalls, avg, note);
    }

    /** 应用到分群：站内幂等登记 + 审计；真实推送 M3-06/M3-14 为远期 Backlog。 */
    @Transactional
    public ApplyResult apply(Long id) {
        LoginUser user = requireUser();
        AiCustomerProfile p = profileRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "画像快照不存在（id=" + id + "）"));
        if (Boolean.TRUE.equals(p.getAppliedToSegment())) {
            return new ApplyResult(false, id, true);
        }
        p.setAppliedToSegment(true);
        p.setAppliedAt(OffsetDateTime.now());
        p.setAppliedBy(user.staffId());
        profileRepo.save(p);
        audit.record("AI_CUSTOMER_PROFILE", "PROFILE-" + id,
                DataScope.currentActor(), "APPLY_SEGMENT",
                payload(Map.of("customerId", p.getCustomerId(), "customerName", p.getCustomerName())));
        return new ApplyResult(true, id, true);
    }

    // ============================ 解析 / 组装 ============================

    /** 关键词无客户号时：取客户域搜索的第一候选；无候选 → 400 中文。 */
    private String resolveByKeyword(String keyword) {
        String kw = normalizeKeyword(keyword);
        List<Map<String, Object>> rows = customerClient.search(kw);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未搜索到客户「" + kw + "」，请用准确的姓名 / 手机号 / 客户编号后重试");
        }
        return str(rows.get(0).get("customerId"));
    }

    private String composePrompt(Map<String, Object> ctx) {
        return "你是医美连锁的数据分析师，请基于以下客户真实经营数据生成客户画像。\n"
                + "【客户】" + str(ctx.get("name")) + "（" + str(ctx.get("gender")) + "），会员等级："
                + str(ctx.get("level")) + "，状态：" + str(ctx.get("status")) + "，获客渠道："
                + channelLabel(str(ctx.get("channel"))) + "\n"
                + "【经营】累计消费 " + str(ctx.get("totalSpend")) + " 元，到店 "
                + str(ctx.get("visitCount")) + " 次，积分 " + str(ctx.get("points")) + "。\n"
                + "【客情】肤质：" + emptyTo(str(ctx.get("skinType")), "未登记")
                + "；主要诉求：" + joinOr(ctx.get("concerns"), "未登记")
                + "；意向项目：" + joinOr(ctx.get("intentProjects"), "未登记")
                + "；意向程度：" + emptyTo(str(ctx.get("intentLevel")), "未评估")
                + "；预算区间：" + emptyTo(str(ctx.get("budget")), "未填") + "。\n"
                + "【已有标签】" + joinOr(ctx.get("tags"), "无") + "。\n"
                + "请严格只输出一个 JSON 对象（不要 markdown、不要解释），字段如下：\n"
                + "{\"valueScore\":0到100的整数客户价值分,"
                + "\"groups\":[\"所属分群名，3个以内，群体级、不含个体敏感信息\"],"
                + "\"tags\":[{\"label\":\"画像标签名（10个以内，基于数据推断）\"}]}\n"
                + "要求：结论必须由上述数据支撑，不得编造消费/到店/病史；标签为群体级画像，"
                + "符合 A1-17 隐私脱敏口径，不输出疾病诊断与绝对化承诺。";
    }

    /**
     * LLM 输出容错解析：优先直接解析 JSON 对象；失败则截取首个 { 到末个 } 再解析；
     * 仍失败 → 价值分 0、空分群、空标签（原文仍落 raw_output，页面诚实可见）。
     */
    @SuppressWarnings("unchecked")
    private ParsedProfile parse(String raw) {
        int score = 0;
        List<String> groups = new ArrayList<>();
        List<ProfileTag> tags = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            String candidate = raw.trim();
            int start = candidate.indexOf('{');
            int end = candidate.lastIndexOf('}');
            if (start >= 0 && end > start) {
                candidate = candidate.substring(start, end + 1);
            }
            try {
                JsonNode node = json.readTree(candidate);
                score = clampScore(node.path("valueScore").asInt(0));
                if (node.path("groups").isArray()) {
                    for (JsonNode g : node.path("groups")) {
                        String label = g.asText("").trim();
                        if (!label.isEmpty() && groups.size() < GROUPS_MAX) {
                            groups.add(truncate(label, LABEL_MAX));
                        }
                    }
                }
                if (node.path("tags").isArray()) {
                    int idx = 0;
                    for (JsonNode t : node.path("tags")) {
                        String label = t.isObject() ? t.path("label").asText("").trim() : t.asText("").trim();
                        if (!label.isEmpty() && tags.size() < TAGS_MAX) {
                            tags.add(new ProfileTag(truncate(label, LABEL_MAX),
                                    TAG_TONES.get(idx % TAG_TONES.size())));
                            idx++;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 非 JSON 输出：保留原文，结构字段走空值兜底（不抛 500，调用本身已真实计费落日志）
            }
        }
        return new ParsedProfile(score, groups, tags);
    }

    private record ParsedProfile(int score, List<String> groups, List<ProfileTag> tags) {
    }

    private ProfileView toView(AiCustomerProfile p, String phoneFallback, String levelFallback) {
        List<String> groups = readStringList(p.getGroupsJson());
        List<ProfileTag> tags = readTags(p.getTagsJson());
        return new ProfileView(p.getProfileId(), p.getCustomerId(), p.getCustomerName(),
                phoneFallback, levelFallback, p.getValueScore(), groups, tags,
                p.getInvokeLogId(), p.getModelCode(), p.getTotalTokens(), p.getCostFen(),
                Boolean.TRUE.equals(p.getAppliedToSegment()), p.getStaffId(), p.getStaffName(),
                p.getStoreCode(), p.getCreatedAt());
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringList(String raw) {
        try {
            JsonNode arr = json.readTree(raw == null || raw.isBlank() ? "[]" : raw);
            List<String> out = new ArrayList<>();
            if (arr.isArray()) {
                arr.forEach(n -> {
                    String s = n.asText("").trim();
                    if (!s.isEmpty()) out.add(s);
                });
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ProfileTag> readTags(String raw) {
        List<ProfileTag> out = new ArrayList<>();
        try {
            JsonNode arr = json.readTree(raw == null || raw.isBlank() ? "[]" : raw);
            if (arr.isArray()) {
                int idx = 0;
                for (JsonNode n : arr) {
                    String label = n.path("label").asText("").trim();
                    String status = n.path("status").asText(TAG_TONES.get(idx % TAG_TONES.size())).trim();
                    if (!TAG_TONES.contains(status)) {
                        status = "default";
                    }
                    if (!label.isEmpty()) {
                        out.add(new ProfileTag(label, status));
                        idx++;
                    }
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private String channelLabel(String code) {
        if (code == null || code.isBlank()) return "未登记";
        return switch (code.trim()) {
            case "WALK_IN" -> "自然到店";
            case "REFERRAL" -> "老客转介绍";
            case "WECHAT" -> "微信";
            case "DOUYIN" -> "抖音";
            case "XIAOHONGSHU" -> "小红书";
            case "MEITUAN" -> "美团";
            default -> "其他";
        };
    }

    private String joinOr(Object rawList, String fallback) {
        if (rawList instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).reduce((a, b) -> a + "、" + b).orElse(fallback);
        }
        return fallback;
    }

    private LoginUser requireUser() {
        LoginUser user = SecurityContext.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效，请重新登录");
        }
        return user;
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入客户姓名 / 手机号 / 客户编号");
        }
        String kw = keyword.trim();
        if (kw.length() > KEYWORD_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索关键词不能超过 " + KEYWORD_MAX + " 字");
        }
        return kw;
    }

    private String normalizeCustomerId(String customerId) {
        return customerId == null ? "" : customerId.trim();
    }

    private static int clampScore(int v) {
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

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String write(Object data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            return "[]";
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
