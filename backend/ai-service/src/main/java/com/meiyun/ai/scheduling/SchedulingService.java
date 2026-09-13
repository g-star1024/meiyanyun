package com.meiyun.ai.scheduling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.client.OrgStaffClient;
import com.meiyun.ai.client.OrgStaffClient.StaffBrief;
import com.meiyun.ai.client.TxnDailyClient;
import com.meiyun.ai.domain.AiInvokeLogRepository;
import com.meiyun.ai.domain.AiSchedulingPlan;
import com.meiyun.ai.domain.AiSchedulingPlanRepository;
import com.meiyun.ai.domain.AiSchedulingSlot;
import com.meiyun.ai.domain.AiSchedulingSlotRepository;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 智能排班（A1-08）：下周 7 天客流由 txn-service 内部端点的最近 14 天真实到店登记按星期均值
 * 预测；员工池由 org-service 内部端点按 6 类排班角色枚举聚合成门店真实在职名单。排班矩阵（
 * 3 班 × 7 天需求槽位 → 员工公平轮转，每周每人 ≤5 班、每天至多 1 班，容量不足如实记缺口）全部
 * 由规则基于真实数据算出；scheduling 功能 invoke 全治理链（角色灰度/门店灰度/敏感词/配额/计费/
 * ai_invoke_log）仅用于生成排班解读摘要与高峰/缺口/公平性建议，模型不参与数字计算。
 *
 * <p>诚实口径：系统内无员工工资数据，slot 成本为页面明示的岗位参考班薪规则估算（非真实薪资）；
 * M2-03 排班后端不存在，无"现排方案"数据源，不做伪造对比，差异/节省指标一律不展示，采纳仅为
 * ai-service 内幂等状态翻转+审计（真实回填 M2-03 为远期 Backlog）；历史到店全为 0 时不造需求。
 */
@Service
public class SchedulingService {

    public static final String FEATURE_CODE = "scheduling";
    public static final String MODEL_VERSION = "v1-2026-09";

    private static final ZoneOffset BJ = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int RAW_MAX = 8000;
    private static final int JSON_MAX = 2000;
    private static final int SUMMARY_MAX = 1000;
    private static final int TITLE_MAX = 64;
    private static final int DETAIL_MAX = 200;

    /** 每个排班人次每班可承接的参考客流（规则参数，非历史拟合）。 */
    private static final int CUSTOMERS_PER_SLOT = 40;
    /** 单员工每周最多排班天数。 */
    private static final int MAX_SLOTS_PER_STAFF = 5;
    private static final int SHIFT_HOURS = 6;

    private static final List<String> DAY_LABELS =
            List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");
    private static final List<ShiftDef> SHIFTS = List.of(
            new ShiftDef("MORNING", "早班", "09-15"),
            new ShiftDef("MID", "中班", "12-18"),
            new ShiftDef("EVENING", "晚班", "15-21"));

    /** 岗位参考班薪（元/6 小时班，规则估算参数；系统无真实工资数据）；键与 org 域 staff.role_code 裸码对齐。 */
    private static final Map<String, Long> ROLE_PAY_FEN = Map.of(
            "STORE_MGR", 60000L,
            "CONSULTANT", 54000L,
            "DOCTOR", 70000L,
            "FRONT_DESK", 36000L);
    private static final long DEFAULT_PAY_FEN = 42000L;
    private static final Map<String, String> ROLE_NAMES = Map.of(
            "STORE_MGR", "店长",
            "CONSULTANT", "咨询师",
            "DOCTOR", "医生/治疗师",
            "FRONT_DESK", "前台/收银");

    private static final String COST_BASIS_NOTE =
            "系统内暂无员工工资数据，人力成本按岗位参考班薪（店长 ¥600/咨询师 ¥540/医生与治疗师 ¥700/前台与收银 ¥360，每班 6 小时）规则估算，非真实薪资；接入薪酬数据后改为实算。";
    private static final String BASELINE_NOTE =
            "M2-03 排班管理后端尚未建设，暂无现排方案数据源，本页不展示现排对比与节省额；采纳仅在 AI 侧登记，真实回填待 M2-03 后端落地。";

    private final AiSchedulingPlanRepository planRepo;
    private final AiSchedulingSlotRepository slotRepo;
    private final AiInvokeLogRepository invokeLogRepo;
    private final FeatureInvokeService featureInvokeService;
    private final OrgStaffClient orgClient;
    private final TxnDailyClient txnClient;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public SchedulingService(AiSchedulingPlanRepository planRepo,
                             AiSchedulingSlotRepository slotRepo,
                             AiInvokeLogRepository invokeLogRepo,
                             FeatureInvokeService featureInvokeService,
                             OrgStaffClient orgClient,
                             TxnDailyClient txnClient,
                             AuditRecorder audit) {
        this.planRepo = planRepo;
        this.slotRepo = slotRepo;
        this.invokeLogRepo = invokeLogRepo;
        this.featureInvokeService = featureInvokeService;
        this.orgClient = orgClient;
        this.txnClient = txnClient;
        this.audit = audit;
    }

    // ============================ DTO ============================

    public record GenerateCmd(String weekStart, String storeCode) {
    }

    public record ForecastItem(String date, String weekday, long forecast, long sampleAvg, int samples) {
    }

    public record ShiftRow(String shiftCode, String shiftName, String shiftTime, List<Integer> counts) {
    }

    public record SlotItem(Long slotId, int dayIndex, String dayLabel, String shiftCode, String shiftName,
                           String staffId, String staffName, String roleCode, String roleName,
                           int hours, long costFen, boolean gap) {
    }

    public record NoteItem(String type, String title, String detail) {
    }

    public record PlanView(Long planId, String weekStart, String storeCode, String status,
                           long forecastTotal, int slotTotal, int staffPoolCount, int gapSlots, long costFen,
                           String summary, List<ForecastItem> forecast, List<ShiftRow> matrix,
                           List<SlotItem> slots, List<NoteItem> notes,
                           String modelCode, Long invokeLogId, Integer totalTokens, Long llmCostFen,
                           String adoptedAt, String adoptedBy, String createdAt,
                           String costBasisNote, String baselineNote) {
    }

    public record SchedulingStats(long planCount, long adoptedCount, long weekInvokes,
                                  String modelVersion, String modelNote) {
    }

    public record HistoryItem(String weekStart, String summary, long forecastTotal, int slotTotal,
                              int gapSlots, long costFen, String status, String createdAt) {
    }

    public record ActionResult(boolean changed, Long planId, String action) {
    }

    private record ShiftDef(String code, String name, String time) {
    }

    // ============================ 业务方法 ============================

    /**
     * 生成下周排班方案：真实员工池 + 最近 14 天真实到店 → 规则矩阵 → scheduling invoke 解读 → 落库。
     * 同周+门店可重生成（新版本多行，读时取最新）。无方法级事务。
     */
    public PlanView generate(GenerateCmd cmd) {
        LoginUser user = requireUser();
        String week = normalizeWeekStart(cmd == null ? null : cmd.weekStart());
        String store = resolveStore(cmd == null ? null : cmd.storeCode(), user);
        LocalDate monday = LocalDate.parse(week, DATE_FMT);

        List<StaffBrief> pool = orgClient.listStoreStaff(store);
        if (pool.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "组织服务暂不可用或本门店暂无在职排班员工（店长/咨询师/医生含治疗师/前台含收银），"
                            + "无法生成排班方案，请稍后重试或先在组织管理维护员工");
        }

        ForecastResult fr = buildForecast(monday, store);
        List<ForecastItem> forecast = fr.items();
        if (fr.sampleDays() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "交易服务暂不可用，最近 14 天到店登记指标未取回，无法预测下周客流，请稍后重试");
        }
        // 到店登记真实为 0 条（到店登记功能暂无沉淀）属真实数据现状：不 502、不编造客流，
        // 退化为「每班 1 人保底覆盖」基线排班，并在解读与预测样本中如实标注。
        boolean zeroArrival = fr.arrivalSum() == 0;

        int[][] demand = buildDemand(forecast, zeroArrival);
        Assignment assignment = assign(pool, demand);
        long forecastTotal = forecast.stream().mapToLong(ForecastItem::forecast).sum();

        FeatureInvokeService.InvokeView v = featureInvokeService.invoke(
                FEATURE_CODE,
                new FeatureInvokeService.InvokeCmd(
                        composePrompt(week, store, forecast, demand, pool, assignment.gapCount, zeroArrival),
                        store == null ? "" : store));

        ParsedNotes parsed = parse(v.content(), forecast, demand, pool, assignment, zeroArrival);

        AiSchedulingPlan p = new AiSchedulingPlan();
        p.setWeekStart(week);
        p.setStoreCode(store == null ? "" : store);
        p.setStatus("DRAFT");
        p.setForecastTotal((int) Math.min(forecastTotal, Integer.MAX_VALUE));
        p.setSlotTotal(assignment.slots.size());
        p.setStaffPoolCount(pool.size());
        p.setGapSlots(assignment.gapCount);
        p.setCostFen(assignment.costFen);
        p.setForecastJson(truncate(writeJson(forecast), JSON_MAX));
        p.setNotesJson(truncate(writeJson(parsed.notes()), JSON_MAX));
        p.setSummary(truncate(parsed.summary(), SUMMARY_MAX));
        p.setRawOutput(truncate(v.content() == null ? "" : v.content(), RAW_MAX));
        p.setInvokeLogId(v.logId());
        p.setModelCode(v.modelCode());
        p.setTotalTokens(v.totalTokens());
        p.setLlmCostFen(v.costFen() == null ? 0L : v.costFen());
        p.setStaffId(user.staffId());
        p.setStaffName(user.staffName());
        AiSchedulingPlan saved = planRepo.save(p);

        List<AiSchedulingSlot> rows = new ArrayList<>();
        for (PlacedSlot ps : assignment.slots) {
            AiSchedulingSlot s = new AiSchedulingSlot();
            s.setPlanId(saved.getPlanId());
            s.setDayIndex(ps.dayIndex);
            s.setShiftCode(ps.shiftCode);
            s.setHours(SHIFT_HOURS);
            if (ps.staff != null) {
                s.setStaffId(ps.staff.staffId());
                s.setStaffName(ps.staff.staffName());
                s.setRoleCode(ps.staff.primaryRole());
                s.setRoleName(roleName(ps.staff.primaryRole()));
                s.setCostFen(rolePay(ps.staff.primaryRole()));
            }
            rows.add(s);
        }
        slotRepo.saveAll(rows);

        audit.record("AI_SCHEDULING", "PLAN-" + saved.getPlanId(),
                DataScope.currentActor(), "GENERATE_SCHEDULE",
                payload(Map.of("planId", saved.getPlanId(), "weekStart", week,
                        "storeCode", store == null ? "" : store,
                        "forecastTotal", forecastTotal,
                        "staffPool", pool.size(), "slots", assignment.slots.size(),
                        "gapSlots", assignment.gapCount,
                        "zeroArrival", zeroArrival)));

        AiSchedulingPlan reread = planRepo.findById(saved.getPlanId()).orElse(saved);
        return toPlanView(reread, slotRepo.findByPlanIdOrderBySlotIdAsc(saved.getPlanId()));
    }

    /** 指定周最新版方案（含甘特矩阵/槽位/解读）；无方案 404 中文引导先生成。 */
    @Transactional(readOnly = true)
    public PlanView plan(String weekStart, String storeCode) {
        LoginUser user = requireUser();
        String week = normalizeWeekStart(weekStart);
        String store = resolveStore(storeCode, user);
        AiSchedulingPlan p = latest(week, store == null ? "" : store);
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    week + " 当周尚未生成 AI 排班方案，请点击「生成排班方案」");
        }
        return toPlanView(p, slotRepo.findByPlanIdOrderBySlotIdAsc(p.getPlanId()));
    }

    /** 采纳方案（站内幂等状态翻转）；M2-03 真实回填为远期 Backlog。 */
    public ActionResult adopt(Long planId) {
        LoginUser user = requireUser();
        AiSchedulingPlan p = planRepo.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "排班方案不存在（id=" + planId + "）"));
        if ("ADOPTED".equals(p.getStatus())) {
            return new ActionResult(false, planId, "adopt");
        }
        p.setStatus("ADOPTED");
        p.setAdoptedAt(OffsetDateTime.now());
        p.setAdoptedBy(user.staffId());
        planRepo.save(p);
        audit.record("AI_SCHEDULING", "PLAN-" + planId,
                DataScope.currentActor(), "ADOPT_PLAN",
                payload(Map.of("planId", planId, "weekStart", p.getWeekStart(),
                        "storeCode", p.getStoreCode() == null ? "" : p.getStoreCode(),
                        "slots", p.getSlotTotal(), "gapSlots", p.getGapSlots())));
        return new ActionResult(true, planId, "adopt");
    }

    /** 页头统计：方案/采纳真实计数 + 本周 scheduling invoke + 诚实模型说明。 */
    @Transactional(readOnly = true)
    public SchedulingStats stats() {
        requireUser();
        OffsetDateTime weekStart = OffsetDateTime.now(BJ).with(DayOfWeek.MONDAY)
                .toLocalDate().atStartOfDay().atOffset(BJ);
        long weekInvokes = invokeLogRepo.countByInvokedAtGreaterThanEqualAndFeatureCode(
                weekStart, FEATURE_CODE);
        return new SchedulingStats(planRepo.count(),
                planRepo.countByStatus("ADOPTED"),
                weekInvokes, MODEL_VERSION,
                "排班矩阵由真实历史到店与在职员工池规则计算，LLM 仅生成解读与建议（scheduling 功能真实出站）；"
                        + "成本为岗位参考班薪规则估算，采纳为站内登记");
    }

    /** 历史方案：按周起始+门店归并取每周最新版，按周倒序，最多 14 条。 */
    @Transactional(readOnly = true)
    public List<HistoryItem> history(String storeCode) {
        LoginUser user = requireUser();
        String store = resolveStore(storeCode, user);
        Map<String, AiSchedulingPlan> latestByKey = new LinkedHashMap<>();
        for (AiSchedulingPlan p : planRepo.findTop60ByOrderByPlanIdDesc()) {
            String sc = p.getStoreCode() == null ? "" : p.getStoreCode();
            if (store != null && !store.equals(sc)) {
                continue;
            }
            latestByKey.putIfAbsent(p.getWeekStart() + "|" + sc, p);
        }
        List<HistoryItem> out = new ArrayList<>();
        for (AiSchedulingPlan p : latestByKey.values()) {
            out.add(new HistoryItem(p.getWeekStart(), p.getSummary(),
                    p.getForecastTotal() == null ? 0L : p.getForecastTotal(),
                    p.getSlotTotal() == null ? 0 : p.getSlotTotal(),
                    p.getGapSlots() == null ? 0 : p.getGapSlots(),
                    p.getCostFen() == null ? 0L : p.getCostFen(),
                    p.getStatus(), bjTime(p.getCreatedAt())));
            if (out.size() >= 14) {
                break;
            }
        }
        return out;
    }

    // ============================ 预测 / 需求 / 分配 ============================

    /**
     * 最近 14 天（weekStart-14 ~ weekStart-1）真实到店登记按星期分组均值；无样本天用全样本均值。
     * 返回值同时给出成功取到指标的天数（sampleDays=0 视为交易服务不可用）与到店总和
     * （arrivalSum=0 但 sampleDays&gt;0 为真实零到店，调用方走保底排班而非报错）。
     */
    private ForecastResult buildForecast(LocalDate monday, String store) {
        Map<DayOfWeek, long[]> byDow = new LinkedHashMap<>();
        long[] overall = new long[]{0L, 0L};
        int sampleDays = 0;
        for (int back = 14; back >= 1; back--) {
            LocalDate d = monday.minusDays(back);
            Map<String, Object> m = txnClient.dailyMetrics(d.format(DATE_FMT), store);
            if (m == null) {
                continue;
            }
            sampleDays++;
            long arrivals = lng(m, "arrivalCount");
            long[] agg = byDow.computeIfAbsent(d.getDayOfWeek(), k -> new long[]{0L, 0L});
            agg[0] += arrivals;
            agg[1]++;
            overall[0] += arrivals;
            overall[1]++;
        }
        long overallAvg = overall[1] == 0 ? 0L : Math.round(overall[0] * 1.0 / overall[1]);
        List<ForecastItem> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = monday.plusDays(i);
            long[] agg = byDow.get(d.getDayOfWeek());
            long avg = agg == null ? overallAvg : Math.round(agg[0] * 1.0 / agg[1]);
            int samples = agg == null ? 0 : (int) agg[1];
            out.add(new ForecastItem(d.format(DATE_FMT), DAY_LABELS.get(i), avg, avg, samples));
        }
        return new ForecastResult(out, sampleDays, overall[0]);
    }

    /**
     * 客流 → 各班次需求：日需求 = round(预测客流 / 人均承接)，周五至周日整体 ×1.15；
     * 日需求按班次权重（工作日 0.35/0.30/0.35，周五~周日晚班加重 0.25/0.30/0.45）最大余数法拆分。
     * zeroArrival（真实零到店历史）时预测客流为 0 无法推导需求，按门店运营保底口径每班排 1 人。
     */
    private int[][] buildDemand(List<ForecastItem> forecast, boolean zeroArrival) {
        int[][] demand = new int[7][3];
        for (int day = 0; day < 7; day++) {
            if (zeroArrival) {
                demand[day] = new int[]{1, 1, 1};
                continue;
            }
            boolean weekend = day >= 4;
            double boost = weekend ? 1.15 : 1.0;
            int total = (int) Math.round(forecast.get(day).forecast() * boost / CUSTOMERS_PER_SLOT);
            double[] w = weekend ? new double[]{0.25, 0.30, 0.45}
                    : new double[]{0.35, 0.30, 0.35};
            demand[day] = largestRemainder(total, w);
        }
        return demand;
    }

    /** 最大余数法把整数 total 按权重拆为整数个数。 */
    private int[] largestRemainder(int total, double[] weights) {
        double sum = 0;
        for (double w : weights) {
            sum += w;
        }
        int[] floors = new int[weights.length];
        double[] raw = new double[weights.length];
        double[] frac = new double[weights.length];
        int assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            raw[i] = total * weights[i] / sum;
            floors[i] = (int) Math.floor(raw[i]);
            frac[i] = raw[i] - floors[i];
            assigned += floors[i];
        }
        int remain = total - assigned;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> frac[i]).reversed());
        for (int k = 0; k < remain; k++) {
            floors[order.get(k % order.size())]++;
        }
        return floors;
    }

    /**
     * 员工公平轮转：逐天逐班按（本周已排数, 工号）升序选第一个当天未排且未超 5 班的员工；
     * 选不出即缺口槽（staff=null）。
     */
    private Assignment assign(List<StaffBrief> pool, int[][] demand) {
        Map<String, Integer> weekly = new LinkedHashMap<>();
        for (StaffBrief s : pool) {
            weekly.put(s.staffId(), 0);
        }
        List<PlacedSlot> slots = new ArrayList<>();
        int gapCount = 0;
        long costFen = 0;
        for (int day = 0; day < 7; day++) {
            java.util.Set<String> usedToday = new java.util.HashSet<>();
            for (int shift = 0; shift < 3; shift++) {
                for (int n = 0; n < demand[day][shift]; n++) {
                    StaffBrief pick = pool.stream()
                            .filter(s -> !usedToday.contains(s.staffId()))
                            .filter(s -> weekly.get(s.staffId()) < MAX_SLOTS_PER_STAFF)
                            .min(Comparator.comparingInt((StaffBrief s) -> weekly.get(s.staffId()))
                                    .thenComparing(StaffBrief::staffId))
                            .orElse(null);
                    if (pick == null) {
                        slots.add(new PlacedSlot(day, SHIFTS.get(shift).code(), null));
                        gapCount++;
                    } else {
                        slots.add(new PlacedSlot(day, SHIFTS.get(shift).code(), pick));
                        usedToday.add(pick.staffId());
                        weekly.merge(pick.staffId(), 1, Integer::sum);
                        costFen += rolePay(pick.primaryRole());
                    }
                }
            }
        }
        return new Assignment(slots, gapCount, costFen, weekly);
    }

    // ============================ LLM 解读 ============================

    private String composePrompt(String week, String store, List<ForecastItem> forecast,
                                 int[][] demand, List<StaffBrief> pool, int gapCount,
                                 boolean zeroArrival) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是医美连锁的门店排班经理。以下是 ").append(week)
                .append(" 起一周（").append(store == null ? "全部门店" : store).append("）的真实排班依据，")
                .append("员工为组织服务内在职排班员工，请据此做排班解读。\n");
        if (zeroArrival) {
            sb.append("【重要数据口径】最近 14 天到店登记真实为 0 条（到店登记功能暂无历史沉淀），")
                    .append("本周不做客流预测，下表客流预测均为 0；排班采用「早/中/晚每班 1 人」运营保底覆盖，")
                    .append("不得把 0 客流解读为门店无生意，也不得编造客流数字。\n");
        } else {
            sb.append("客流为最近 14 天真实到店登记按星期均值。\n");
        }
        sb.append("【下周客流预测（人次）】");
        for (int i = 0; i < 7; i++) {
            ForecastItem f = forecast.get(i);
            sb.append(f.weekday()).append(f.forecast()).append("（三班需求 ")
                    .append(demand[i][0]).append('/').append(demand[i][1]).append('/').append(demand[i][2])
                    .append("）");
            sb.append(i == 6 ? "。\n" : "；");
        }
        sb.append("【在职排班员工池 ").append(pool.size()).append(" 人】");
        for (StaffBrief s : pool) {
            sb.append(s.staffId()).append(' ').append(s.staffName()).append('（')
                    .append(roleName(s.primaryRole())).append("）、");
        }
        sb.append("\n【规则计算结果】建议排班槽位 ").append(demandTotal(demand))
                .append(" 人次，员工每周最多 5 班，当前未覆盖缺口 ").append(gapCount).append(" 个。\n");
        sb.append("请严格只输出一个 JSON 对象（不要 markdown、不要解释），字段：\n")
                .append("{\"summary\":\"不超过 120 字的排班解读，概括客流高峰日与排班/缺口真实情况\",")
                .append("\"notes\":[{\"type\":\"peak 或 gap 或 fairness\",\"title\":\"不超过 30 字\",")
                .append("\"detail\":\"不超过 100 字，必须由上述真实数据支撑\"}]}\n")
                .append("要求：notes 给 3~5 条，peak 讲高峰日、gap 讲缺口（无缺口则说明覆盖充足）、")
                .append("fairness 讲员工负荷公平性；只能使用给出的客流/员工/槽位数据，不得编造员工工资、")
                .append("现排方案、历史营收与疾病诊疗内容，不做绝对化承诺，符合 A1-17 隐私脱敏口径。");
        return sb.toString();
    }

    /**
     * LLM 输出容错解析：截首 { 到末 }；summary 缺失/非 JSON 用真实数值兜底；
     * notes 类型非法归 peak、空数组走规则兜底（不抛 500，调用已真实计费落日志）。
     */
    private ParsedNotes parse(String raw, List<ForecastItem> forecast, int[][] demand,
                              List<StaffBrief> pool, Assignment assignment, boolean zeroArrival) {
        String summary = fallbackSummary(forecast, demand, pool, assignment, zeroArrival);
        List<NoteItem> notes = new ArrayList<>();
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
                JsonNode arr = node.path("notes");
                if (arr.isArray()) {
                    for (JsonNode item : arr) {
                        String title = item.path("title").asText("").trim();
                        if (title.isEmpty()) {
                            continue;
                        }
                        String detail = item.path("detail").asText("").trim();
                        notes.add(new NoteItem(normalizeType(item.path("type").asText("").trim()),
                                truncate(title, TITLE_MAX),
                                truncate(detail.isEmpty() ? title : detail, DETAIL_MAX)));
                        if (notes.size() >= 5) {
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 非 JSON 输出：摘要与建议走真实数据兜底
            }
        }
        if (notes.isEmpty()) {
            notes = fallbackNotes(forecast, demand, pool, assignment, zeroArrival);
        }
        if (zeroArrival) {
            // 零到店样本为数据现状而非结论：无论模型输出与否，首条固定为口径说明，防止把 0 客流误读为无生意
            NoteItem basis = new NoteItem("gap", "无历史客流样本，按保底覆盖排班",
                    "最近 14 天到店登记真实为 0 条，本周不做客流预测，按早/中/晚每班 1 人运营保底排，待到店登记有沉淀后自动恢复按客流排班");
            notes = new ArrayList<>(notes);
            notes.removeIf(n -> "无历史客流样本，按保底覆盖排班".equals(n.title()));
            notes.add(0, basis);
            if (notes.size() > 5) {
                notes = notes.subList(0, 5);
            }
        }
        return new ParsedNotes(summary, notes);
    }

    private String normalizeType(String raw) {
        return switch (raw) {
            case "peak", "gap", "fairness" -> raw;
            default -> "peak";
        };
    }

    private String fallbackSummary(List<ForecastItem> forecast, int[][] demand,
                                   List<StaffBrief> pool, Assignment a, boolean zeroArrival) {
        if (zeroArrival) {
            return "最近 14 天无真实到店登记样本，本周不做客流预测，按早/中/晚每班 1 人运营保底排，"
                    + "规则排班 " + demandTotal(demand) + " 人次；在职排班员工 " + pool.size()
                    + " 人，未覆盖缺口 " + a.gapCount + " 个（每人每周最多 5 班）。";
        }
        ForecastItem peak = forecast.stream()
                .max(Comparator.comparingLong(ForecastItem::forecast)).orElse(forecast.get(0));
        long total = forecast.stream().mapToLong(ForecastItem::forecast).sum();
        return "下周预测到店 " + total + " 人次，高峰为" + peak.weekday() + "（约 " + peak.forecast()
                + " 人次）；规则建议排班 " + demandTotal(demand) + " 人次，在职排班员工 " + pool.size()
                + " 人，未覆盖缺口 " + a.gapCount + " 个（每人每周最多 5 班）。";
    }

    private List<NoteItem> fallbackNotes(List<ForecastItem> forecast, int[][] demand,
                                         List<StaffBrief> pool, Assignment a, boolean zeroArrival) {
        List<NoteItem> out = new ArrayList<>();
        if (zeroArrival) {
            out.add(new NoteItem("peak", "保底覆盖每班 1 人",
                    "无历史客流可推导需求，按门店运营底线早/中/晚各 1 人共 21 槽位，客流数据沉淀后自动改按预测排班"));
        } else {
            ForecastItem peak = forecast.stream()
                    .max(Comparator.comparingLong(ForecastItem::forecast)).orElse(forecast.get(0));
            out.add(new NoteItem("peak", "高峰日：" + peak.weekday() + "重点排兵",
                    peak.weekday() + "预测到店约 " + peak.forecast() + " 人次，建议优先保证晚班在岗"));
        }
        if (a.gapCount > 0) {
            out.add(new NoteItem("gap", "存在 " + a.gapCount + " 个未覆盖缺口",
                    "在职排班员工 " + pool.size() + " 人、每周每人最多 5 班，容量不足，"
                            + "建议在排班管理落地后协调兼岗或临时用工"));
        } else {
            out.add(new NoteItem("gap", "班次需求全部覆盖",
                    pool.size() + " 名在职员工可覆盖本周全部 " + demandTotal(demand) + " 个排班槽位"));
        }
        int maxLoad = a.weekly.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int minLoad = a.weekly.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        out.add(new NoteItem("fairness", "负荷极差 " + (maxLoad - minLoad) + " 班",
                "排班按当周已排数公平轮转，最多者 " + maxLoad + " 班、最少者 " + minLoad + " 班"));
        return out;
    }

    // ============================ 视图 / 工具 ============================

    private PlanView toPlanView(AiSchedulingPlan p, List<AiSchedulingSlot> rows) {
        List<ForecastItem> forecast = readList(p.getForecastJson(),
                new TypeReference<List<ForecastItem>>() {});
        List<NoteItem> notes = readList(p.getNotesJson(),
                new TypeReference<List<NoteItem>>() {});

        int[][] counts = new int[3][7];
        List<SlotItem> slots = new ArrayList<>();
        for (AiSchedulingSlot s : rows) {
            int shiftIdx = switch (s.getShiftCode()) {
                case "MORNING" -> 0;
                case "MID" -> 1;
                default -> 2;
            };
            counts[shiftIdx][s.getDayIndex()]++;
            boolean gap = s.getStaffId() == null || s.getStaffId().isBlank();
            slots.add(new SlotItem(s.getSlotId(), s.getDayIndex(), DAY_LABELS.get(s.getDayIndex()),
                    s.getShiftCode(), shiftName(s.getShiftCode()),
                    gap ? "" : s.getStaffId(), s.getStaffName(),
                    s.getRoleCode() == null ? "" : s.getRoleCode(),
                    s.getRoleName() == null ? "" : s.getRoleName(),
                    s.getHours() == null ? SHIFT_HOURS : s.getHours(),
                    s.getCostFen() == null ? 0L : s.getCostFen(), gap));
        }
        List<ShiftRow> matrix = List.of(
                new ShiftRow("MORNING", "早班", "09-15", intsToList(counts[0])),
                new ShiftRow("MID", "中班", "12-18", intsToList(counts[1])),
                new ShiftRow("EVENING", "晚班", "15-21", intsToList(counts[2])));

        return new PlanView(p.getPlanId(), p.getWeekStart(),
                p.getStoreCode() == null ? "" : p.getStoreCode(), p.getStatus(),
                p.getForecastTotal() == null ? 0L : p.getForecastTotal(),
                p.getSlotTotal() == null ? 0 : p.getSlotTotal(),
                p.getStaffPoolCount() == null ? 0 : p.getStaffPoolCount(),
                p.getGapSlots() == null ? 0 : p.getGapSlots(),
                p.getCostFen() == null ? 0L : p.getCostFen(),
                p.getSummary(), forecast, matrix, slots, notes,
                p.getModelCode(), p.getInvokeLogId(), p.getTotalTokens(),
                p.getLlmCostFen() == null ? 0L : p.getLlmCostFen(),
                p.getAdoptedAt() == null ? "" : bjTime(p.getAdoptedAt()),
                p.getAdoptedBy() == null ? "" : p.getAdoptedBy(),
                bjTime(p.getCreatedAt()), COST_BASIS_NOTE, BASELINE_NOTE);
    }

    private AiSchedulingPlan latest(String week, String store) {
        return planRepo.findByWeekStartAndStoreCodeOrderByPlanIdDesc(week, store).stream()
                .findFirst().orElse(null);
    }

    private int demandTotal(int[][] demand) {
        int t = 0;
        for (int[] row : demand) {
            for (int n : row) {
                t += n;
            }
        }
        return t;
    }

    private static String shiftName(String code) {
        return switch (code) {
            case "MORNING" -> "早班";
            case "EVENING" -> "晚班";
            default -> "中班";
        };
    }

    private static String roleName(String code) {
        return ROLE_NAMES.getOrDefault(code, code == null || code.isBlank() ? "未分配" : code);
    }

    private static long rolePay(String code) {
        return ROLE_PAY_FEN.getOrDefault(code, DEFAULT_PAY_FEN);
    }

    private static List<Integer> intsToList(int[] a) {
        List<Integer> out = new ArrayList<>(a.length);
        for (int n : a) {
            out.add(n);
        }
        return out;
    }

    private <T> List<T> readList(String raw, TypeReference<List<T>> type) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<T> v = json.readValue(raw, type);
            return v == null ? List.of() : v;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String resolveStore(String input, LoginUser user) {
        if (input != null && !input.isBlank() && !"ALL".equalsIgnoreCase(input.trim())) {
            return input.trim();
        }
        return user.storeCode() == null || user.storeCode().isBlank() ? null : user.storeCode();
    }

    /** 空则取本周一；须为合法 yyyy-MM-dd 且为周一。 */
    private String normalizeWeekStart(String weekStart) {
        LocalDate monday;
        if (weekStart == null || weekStart.isBlank()) {
            monday = LocalDate.now(BJ).with(DayOfWeek.MONDAY);
        } else {
            String w = weekStart.trim();
            try {
                monday = LocalDate.parse(w, DATE_FMT);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "周起始日 weekStart 格式非法，需 yyyy-MM-dd（如 2026-09-14）：" + w);
            }
            if (monday.getDayOfWeek() != DayOfWeek.MONDAY) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "周起始日必须是周一：" + w);
            }
        }
        return monday.format(DATE_FMT);
    }

    private LoginUser requireUser() {
        LoginUser user = SecurityContext.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效，请重新登录");
        }
        return user;
    }

    private String bjTime(OffsetDateTime t) {
        return t == null ? "" : t.withOffsetSameInstant(BJ).format(DT_FMT);
    }

    private static long lng(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o == null ? 0L : ((Number) o).longValue();
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

    private record PlacedSlot(int dayIndex, String shiftCode, StaffBrief staff) {
    }

    private record Assignment(List<PlacedSlot> slots, int gapCount, long costFen,
                              Map<String, Integer> weekly) {
    }

    private record ParsedNotes(String summary, List<NoteItem> notes) {
    }

    /** 预测结果：七日预测 + 成功取到指标的天数（0=交易服务不可用）+ 到店总和（0 且有样本=真实零到店）。 */
    private record ForecastResult(List<ForecastItem> items, int sampleDays, long arrivalSum) {
    }
}
