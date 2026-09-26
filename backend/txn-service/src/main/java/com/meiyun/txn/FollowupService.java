package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.txn.audit.AuditRecorder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 随访独立域服务（P5-B30 术后随访 SOP 引擎）：计数聚合 / 分页列表 / 登记回访 / 标记无需回访。
 *
 * <p>SOP 节点由 {@link FollowupScheduler} 在治疗完成 AFTER_COMMIT 自动排程，本服务只负责读与核销。
 * 铁律：数据域一律 storeSpec 门店全见（显式他店码 404，对齐工作台/随访台账本店全见口径）；
 * 状态机非法流转中文 400（仅 PENDING 可核销）；全写动作审计（bizType=FOLLOWUP）。
 * 满意度/恢复情况枚举做白名单校验，不良反应必须填写说明。</p>
 *
 * <p>P6-B101 随访结构化分析：trend 按 done_at（+8）分桶聚合满意度/不良反应趋势；
 * 不良反应处置台——核销勾选不良反应自动置 adverseStatus=OPEN，POST /{id}/adverse-handle
 * 白名单 OPEN/PROCESSING/RESOLVED 流转（RESOLVED 必填处置说明，同状态同备注幂等不重复审计）。</p>
 */
@Service
public class FollowupService {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_DONE = "DONE";
    public static final String ST_SKIPPED = "SKIPPED";

    private static final Set<String> METHODS = Set.of("PHONE", "WECHAT", "IN_STORE");
    private static final Set<String> RECOVERY = Set.of("GOOD", "NORMAL", "POOR");
    /** 不良反应处置状态白名单（处置台）：待处置/处置中/已闭环。 */
    private static final Set<String> ADVERSE_STATUSES = Set.of("OPEN", "PROCESSING", "RESOLVED");
    /** 趋势统计区间上限（天）。 */
    private static final long TREND_MAX_DAYS = 366;
    private static final ZoneOffset BIZ_TZ = ZoneOffset.ofHours(8);

    private final FollowupRepository followupRepo;
    private final FollowupNoGenerator noGen;
    private final ApptRefNameResolver names;
    private final AuditRecorder audit;

    @PersistenceContext
    private EntityManager em;

    public FollowupService(FollowupRepository followupRepo, FollowupNoGenerator noGen,
                           ApptRefNameResolver names, AuditRecorder audit) {
        this.followupRepo = followupRepo;
        this.noGen = noGen;
        this.names = names;
        this.audit = audit;
    }

    /** 登记回访入参：满意度 1-5、恢复情况必填；不良反应勾选时说明必填；回访方式可空（沿用排程方式）。 */
    public record CompleteCmd(Integer satisfaction, String recovery, Boolean adverseReaction,
                              String adverseNote, Boolean needRevisit, String note, String method) {}

    /**
     * 手工建普通随访入参（followup:create）：客户号/项目/服务日期/计划回访日期必填；
     * 关联订单号仅透传截断不反查订单；方式可空（默认电话）。
     */
    public record CreateCmd(String customerId, String project, String relatedOrderNo,
                            LocalDate serviceDate, LocalDate planDate, String method) {}

    /** 趋势点（P6-B101）：分桶起始日（按天=当日；按周=周一）+ 已回访数/平均满意度（一位小数）/不良反应数。 */
    public record TrendPoint(LocalDate bucket, long doneCount, BigDecimal avgSatisfaction, long adverseCount) {}

    // ==================== 计数（工作台两卡 / 随访台账角标） ====================

    /**
     * 本店随访计数（工作台卡片 / 随访台账 KPI 与三 tab 角标）：
     * 旧两键 sopPending=PENDING 且属 SOP 批次、sopOverdue=再叠加 planDate 早于今日（+8 按天），语义不动；
     * 新增七键：pending/todayPending/overdue/done/skipped（五计数）、avgSatisfaction（DONE 平均星，保留一位小数）、
     * adverseCount（不良反应条数）。返回值含 Long 与 BigDecimal，故值类型为 Object。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stats(String storeCode) {
        Specification<Followup> base = scoped(storeCode);
        LocalDate today = LocalDate.now(BIZ_TZ);
        Specification<Followup> pending = base.and(eqStatus(ST_PENDING));
        Specification<Followup> pendingSop = pending
                .and((root, q, cb) -> cb.isNotNull(root.get("sopBatchId")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sopPending", followupRepo.count(pendingSop));
        out.put("sopOverdue", followupRepo.count(pendingSop
                .and((root, q, cb) -> cb.lessThan(root.get("planDate"), today))));
        out.put("pending", followupRepo.count(pending));
        out.put("todayPending", followupRepo.count(pending
                .and((root, q, cb) -> cb.equal(root.get("planDate"), today))));
        out.put("overdue", followupRepo.count(pending
                .and((root, q, cb) -> cb.lessThan(root.get("planDate"), today))));
        out.put("done", followupRepo.count(base.and(eqStatus(ST_DONE))));
        out.put("skipped", followupRepo.count(base.and(eqStatus(ST_SKIPPED))));
        out.put("avgSatisfaction", avgSatisfaction(base));
        out.put("adverseCount", followupRepo.count(base
                .and((root, q, cb) -> cb.isTrue(root.get("adverseReaction")))));
        return out;
    }

    /** DONE 且有满意度记录的平均星（数据域内），四舍五入保留一位小数；无记录返回 0。 */
    private BigDecimal avgSatisfaction(Specification<Followup> base) {
        var cb = em.getCriteriaBuilder();
        CriteriaQuery<Double> cq = cb.createQuery(Double.class);
        Root<Followup> root = cq.from(Followup.class);
        cq.select(cb.avg(root.get("satisfaction").as(Double.class)));
        Predicate scope = base.toPredicate(root, cq, cb);
        Predicate p = cb.and(cb.equal(root.get("status"), ST_DONE), root.get("satisfaction").isNotNull());
        cq.where(scope == null ? p : cb.and(scope, p));
        // 聚合保证单行：AVG 无匹配返回 null，getSingleResult 正常得 null；
        // 不能用 getResultStream().findFirst()（含 null 单元素时其内部 requireNonNull 直接 NPE）。
        Double avg = em.createQuery(cq).getSingleResult();
        if (avg == null || avg.isNaN()) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP);
    }

    // ==================== 满意度趋势（P6-B101 随访结构化分析） ====================

    /**
     * 满意度趋势：数据域内 DONE 随访按 done_at（+8）分桶聚合，返回稠密序列（区间内无数据的桶补零，
     * 前端折线图直用）。from/to 缺省近 30 天；区间最长 366 天；granularity=day|week（周以周一为桶首）。
     */
    @Transactional(readOnly = true)
    public List<TrendPoint> trend(String storeCode, LocalDate from, LocalDate to, String granularity) {
        LocalDate end = to == null ? LocalDate.now(BIZ_TZ) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw badRequest("统计起始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > TREND_MAX_DAYS) {
            throw badRequest("统计区间最长 " + TREND_MAX_DAYS + " 天，请缩小日期范围");
        }
        String gran = blank(granularity) ? "day" : granularity.trim().toLowerCase();
        if (!gran.equals("day") && !gran.equals("week")) {
            throw badRequest("非法统计粒度: " + granularity + "（取值 day/week）");
        }
        boolean weekly = gran.equals("week");

        OffsetDateTime fromTs = start.atStartOfDay().atOffset(BIZ_TZ);
        OffsetDateTime toTs = end.plusDays(1).atStartOfDay().atOffset(BIZ_TZ);
        Specification<Followup> spec = scoped(storeCode)
                .and(eqStatus(ST_DONE))
                .and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("doneAt"), fromTs))
                .and((root, q, cb) -> cb.lessThan(root.get("doneAt"), toTs));

        Map<LocalDate, long[]> agg = new LinkedHashMap<>();
        Map<LocalDate, double[]> sat = new LinkedHashMap<>();
        LocalDate first = weekly ? start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : start;
        for (LocalDate d = first; !d.isAfter(end); d = weekly ? d.plusWeeks(1) : d.plusDays(1)) {
            agg.put(d, new long[2]);
            sat.put(d, new double[2]);
        }
        for (Followup f : followupRepo.findAll(spec)) {
            if (f.getDoneAt() == null) continue;
            LocalDate day = f.getDoneAt().atZoneSameInstant(BIZ_TZ).toLocalDate();
            LocalDate bucket = weekly ? day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : day;
            long[] counts = agg.get(bucket);
            if (counts == null) continue;
            counts[0]++;
            if (f.isAdverseReaction()) counts[1]++;
            if (f.getSatisfaction() != null) {
                double[] s = sat.get(bucket);
                s[0] += f.getSatisfaction();
                s[1]++;
            }
        }
        return agg.entrySet().stream()
                .map(e -> {
                    double[] s = sat.get(e.getKey());
                    BigDecimal avg = s[1] == 0 ? BigDecimal.ZERO
                            : BigDecimal.valueOf(s[0] / s[1]).setScale(1, RoundingMode.HALF_UP);
                    return new TrendPoint(e.getKey(), e.getValue()[0], avg, e.getValue()[1]);
                })
                .toList();
    }

    /**
     * 随访分页列表：storeSpec 门店全见；可按状态/客户/批次/只看 SOP 节点叠加过滤；
     * P6-B101 处置台：adverseOnly=true 只看不良反应，adverseStatus 按处置状态过滤
     * （OPEN 兼容历史空值=已勾选不良反应但尚未处置）；keyword 对客户名/项目/关联订单号做小写包含模糊
     * （OR LIKE，排序后端固定防任意字段排序）。排序固定 planDate 升序、id 升序（最早待回访在前）。
     */
    @Transactional(readOnly = true)
    public Page<Followup> page(String storeCode, String status, String customerId,
                               String sopBatchId, Boolean sopOnly, Boolean adverseOnly, String adverseStatus,
                               String keyword, Pageable pageable) {
        Specification<Followup> spec = scoped(storeCode);
        if (!blank(status)) {
            spec = spec.and(eqStatus(status.trim()));
        }
        if (!blank(customerId)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("customerId"), customerId.trim()));
        }
        if (!blank(sopBatchId)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("sopBatchId"), sopBatchId.trim()));
        }
        if (Boolean.TRUE.equals(sopOnly)) {
            spec = spec.and((root, q, cb) -> cb.isNotNull(root.get("sopBatchId")));
        }
        if (Boolean.TRUE.equals(adverseOnly)) {
            spec = spec.and((root, q, cb) -> cb.isTrue(root.get("adverseReaction")));
        }
        String advStatus = trimToNull(adverseStatus);
        if (advStatus != null) {
            if (!ADVERSE_STATUSES.contains(advStatus)) {
                throw badRequest("非法处置状态: " + adverseStatus + "（取值 OPEN/PROCESSING/RESOLVED）");
            }
            spec = spec.and((root, q, cb) -> {
                Predicate adverse = cb.isTrue(root.get("adverseReaction"));
                if ("OPEN".equals(advStatus)) {
                    return cb.and(adverse, cb.or(cb.isNull(root.get("adverseStatus")),
                            cb.equal(root.get("adverseStatus"), "OPEN")));
                }
                return cb.and(adverse, cb.equal(root.get("adverseStatus"), advStatus));
            });
        }
        String kw = trimToNull(keyword);
        if (kw != null) {
            String like = "%" + kw.toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("customerName")), like),
                    cb.like(cb.lower(root.get("project")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("relatedOrderNo"), cb.literal(""))), like)));
        }
        PageRequest page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.asc("planDate"), Sort.Order.asc("id")));
        return followupRepo.findAll(spec, page);
    }

    @Transactional(readOnly = true)
    public Followup get(Long id) {
        return requireFollowup(id);
    }

    // ==================== 手工建普通随访（随访工作台「新建回访计划」） ====================

    /**
     * 手工建普通随访（sopStage=MANUAL、PENDING）。1:1 对齐 EmrService.create 范式：
     * 门店取 JWT 本店（不信入参，空 400）；customerId 必填且经客户域解析（失败引导建档），姓名以解析为准；
     * project/serviceDate/planDate 必填；planDate 不得早于服务日期；method 白名单默认电话；
     * 关联订单号仅透传截断 32 不反查订单；全动作审计（CREATE）。
     */
    @Transactional
    public Followup create(CreateCmd cmd) {
        String actor = DataScope.currentActor();
        LoginUser user = DataScope.current();
        String storeCode = user == null ? null : user.storeCode();
        if (blank(storeCode)) {
            throw badRequest("当前登录人未归属门店，无法新建回访计划");
        }
        if (blank(cmd.customerId())) {
            throw badRequest("请先检索并选择客户后再新建回访计划（不接收未落客户号的自由姓名单）");
        }
        String customerId = cmd.customerId().trim();
        String customerName = names.customerNames(List.of(customerId)).get(customerId);
        if (customerName == null) {
            throw badRequest("客户不存在: " + customerId + "（请先到客情建档）");
        }
        String project = trimToNull(cmd.project());
        if (project == null) {
            throw badRequest("请填写回访项目（如：水光针术后关怀）");
        }
        LocalDate serviceDate = cmd.serviceDate();
        if (serviceDate == null) {
            throw badRequest("请选择服务日期");
        }
        LocalDate planDate = cmd.planDate();
        if (planDate == null) {
            throw badRequest("请选择计划回访日期");
        }
        if (planDate.isBefore(serviceDate)) {
            throw badRequest("计划回访日期不能早于服务日期");
        }
        String method = defaultIfBlank(cmd.method(), "PHONE");
        if (!METHODS.contains(method)) {
            throw badRequest("非法回访方式: " + method);
        }

        Followup f = new Followup();
        f.setFollowupNo(noGen.nextFollowupNo());
        f.setCustomerId(customerId);
        f.setCustomerName(customerName);
        f.setProject(truncate(project, 128));
        f.setRelatedOrderNo(truncate(trimToNull(cmd.relatedOrderNo()), 32));
        f.setStoreCode(storeCode);
        f.setServiceDate(serviceDate);
        f.setPlanDate(planDate);
        f.setMethod(method);
        f.setStatus(ST_PENDING);
        f.setSopStage("MANUAL");
        f.setEscalated(false);
        f.setAdverseReaction(false);
        f.setNeedRevisit(false);
        Followup saved = followupRepo.save(f);
        audit.record("FOLLOWUP", f.getFollowupNo(), actor, "CREATE",
                "{\"customer\":\"" + esc(customerId) + "\",\"project\":\"" + esc(f.getProject())
                        + "\",\"serviceDate\":\"" + serviceDate + "\",\"planDate\":\"" + planDate
                        + "\",\"method\":\"" + method + "\"}");
        return saved;
    }

    // ==================== 核销（登记回访 / 无需回访） ====================

    /** 登记回访结果：PENDING → DONE；盖当前 JWT 人（姓名经组织解析，失败回退工号）。 */
    @Transactional
    public Followup complete(Long id, CompleteCmd cmd) {
        Followup f = requireFollowup(id);
        if (!ST_PENDING.equals(f.getStatus())) {
            throw badRequest("仅待回访状态可登记回访结果，当前状态: " + f.getStatus());
        }
        if (cmd.satisfaction() == null || cmd.satisfaction() < 1 || cmd.satisfaction() > 5) {
            throw badRequest("满意度为必填项，取值 1-5 星");
        }
        String recovery = cmd.recovery() == null ? null : cmd.recovery().trim();
        if (blank(recovery) || !RECOVERY.contains(recovery)) {
            throw badRequest("恢复情况为必填项，取值 GOOD/NORMAL/POOR");
        }
        boolean adverse = Boolean.TRUE.equals(cmd.adverseReaction());
        if (adverse && blank(cmd.adverseNote())) {
            throw badRequest("已勾选不良反应，请填写不良反应说明（便于转投诉/医疗风险跟进）");
        }
        if (!blank(cmd.method()) && !METHODS.contains(cmd.method().trim())) {
            throw badRequest("非法回访方式: " + cmd.method());
        }
        String actor = DataScope.currentActor();
        String actorName = names.staffNames(List.of(actor)).getOrDefault(actor, actor);
        OffsetDateTime now = OffsetDateTime.now();

        f.setStatus(ST_DONE);
        f.setSatisfaction(cmd.satisfaction());
        f.setRecovery(recovery);
        f.setAdverseReaction(adverse);
        f.setAdverseNote(adverse ? truncate(cmd.adverseNote().trim(), 500) : null);
        if (adverse) {
            f.setAdverseStatus("OPEN");
        }
        f.setNeedRevisit(Boolean.TRUE.equals(cmd.needRevisit()));
        f.setNote(truncate(trimToNull(cmd.note()), 65535));
        if (!blank(cmd.method())) {
            f.setMethod(cmd.method().trim());
        }
        f.setFollowupByName(actorName);
        f.setDoneAt(now);
        Followup saved = followupRepo.save(f);
        audit.record("FOLLOWUP", f.getFollowupNo(), actor, "COMPLETE",
                "{\"satisfaction\":" + cmd.satisfaction()
                        + ",\"recovery\":\"" + recovery + "\""
                        + ",\"adverseReaction\":" + adverse
                        + ",\"needRevisit\":" + f.isNeedRevisit() + "}");
        return saved;
    }

    /** 标记无需回访：PENDING → SKIPPED；原因必填（客户明确拒绝/失联等留痕）。 */
    @Transactional
    public Followup skip(Long id, String reason) {
        Followup f = requireFollowup(id);
        if (!ST_PENDING.equals(f.getStatus())) {
            throw badRequest("仅待回访状态可标记无需回访，当前状态: " + f.getStatus());
        }
        String why = trimToNull(reason);
        if (why == null) {
            throw badRequest("请填写无需回访的原因（如客户明确拒绝、失联等）");
        }
        String actor = DataScope.currentActor();
        String actorName = names.staffNames(List.of(actor)).getOrDefault(actor, actor);
        OffsetDateTime now = OffsetDateTime.now();

        f.setStatus(ST_SKIPPED);
        f.setNote(truncate(why, 65535));
        f.setFollowupByName(actorName);
        f.setDoneAt(now);
        Followup saved = followupRepo.save(f);
        audit.record("FOLLOWUP", f.getFollowupNo(), actor, "SKIP",
                "{\"reason\":\"" + esc(why) + "\"}");
        return saved;
    }

    // ==================== 不良反应处置台（P6-B101） ====================

    /**
     * 不良反应处置：仅已登记不良反应（adverseReaction=true）的随访可处置，否则 400；
     * status 白名单 OPEN/PROCESSING/RESOLVED，RESOLVED 必填处置结果说明（医疗风险留痕）；
     * 同状态同备注重复提交幂等直接返回（不重复审计、不刷新处置时间）；处置审计 ADVERSE_HANDLE。
     */
    @Transactional
    public Followup adverseHandle(Long id, String status, String note) {
        Followup f = requireFollowup(id);
        if (!f.isAdverseReaction()) {
            throw badRequest("该随访未登记不良反应，无需处置");
        }
        String st = status == null ? "" : status.trim();
        if (!ADVERSE_STATUSES.contains(st)) {
            throw badRequest("非法处置状态: " + status + "（取值 OPEN/PROCESSING/RESOLVED）");
        }
        String handleNote = trimToNull(note);
        if ("RESOLVED".equals(st) && handleNote == null) {
            throw badRequest("闭环处置请填写处置结果说明（便于医疗风险跟进留痕）");
        }
        if (st.equals(f.getAdverseStatus()) && Objects.equals(handleNote, f.getAdverseHandleNote())) {
            return f;
        }
        String actor = DataScope.currentActor();
        String actorName = names.staffNames(List.of(actor)).getOrDefault(actor, actor);

        f.setAdverseStatus(st);
        f.setAdverseHandleNote(truncate(handleNote, 500));
        f.setAdverseHandleBy(actorName);
        f.setAdverseHandleAt(OffsetDateTime.now());
        Followup saved = followupRepo.save(f);
        audit.record("FOLLOWUP", f.getFollowupNo(), actor, "ADVERSE_HANDLE",
                "{\"status\":\"" + st + "\""
                        + (handleNote == null ? "" : ",\"note\":\"" + esc(handleNote) + "\"") + "}");
        return saved;
    }

    // ==================== 内部 ====================

    /** 门店数据域：storeSpec 全见；显式他店码 404（不暴露存在性）。 */
    private Specification<Followup> scoped(String storeCode) {
        if (!blank(storeCode) && !DataScope.canReadStore(storeCode)) {
            throw notFound("数据不存在或无权查看");
        }
        Specification<Followup> spec = DataScope.storeSpec("storeCode");
        if (!blank(storeCode)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        return spec;
    }

    private Followup requireFollowup(Long id) {
        Followup f = followupRepo.findById(id)
                .orElseThrow(() -> notFound("数据不存在或无权查看"));
        if (!DataScope.canReadStore(f.getStoreCode())) {
            throw notFound("数据不存在或无权查看");
        }
        return f;
    }

    private static Specification<Followup> eqStatus(String status) {
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String defaultIfBlank(String s, String fallback) {
        String t = trimToNull(s);
        return t == null ? fallback : t;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
