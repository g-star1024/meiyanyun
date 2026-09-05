package com.meiyun.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 封账服务（B7，DESIGN §9.2）：日结 / 月结期间锁。
 *
 * <p>封账语义：fund_entry 分录本就 append-only 不可改，封账的实质是「阻断新分录落入已闭期间」。
 * 封账后该「期间 × 门店」任何 occurredAt 落入期间的新分录（含经营域补录/重投）在
 * {@link FundEntryService#postOne} 被 422 拒绝，提示差错走 ADJUST 调平（ADJUST 分录
 * occurredAt=当前时间，落当前期间，天然不撞闭期）。封账永久、无解封入口（红线：已封账不可篡改）。
 *
 * <p>期间口径与 fund_entry 一致：occurred_at 按 UTC 归一；DAY 键 yyyy-MM-dd（UTC 半开
 * [date, date+1d)），MONTH 键 yyyy-MM（UTC 月首）。写接口四件套：校验、幂等（唯一约束
 * (period_type, period_key, store_code) 重放）、全审计（FIN_SETTLE 落 audit_log）、中文错误。
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    static final Set<String> PERIOD_TYPES = Set.of("DAY", "MONTH");

    private final SettlementPeriodRepository settlementRepo;
    private final FundEntryRepository entryRepo;
    private final FinanceAggregationService aggregation;
    private final FinanceAuditRecorder audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SettlementService(SettlementPeriodRepository settlementRepo,
                             FundEntryRepository entryRepo,
                             FinanceAggregationService aggregation,
                             FinanceAuditRecorder audit) {
        this.settlementRepo = settlementRepo;
        this.entryRepo = entryRepo;
        this.aggregation = aggregation;
        this.audit = audit;
    }

    /**
     * 封账：POST /api/finance/settlement。
     * body：periodType=DAY|MONTH、periodKey（DAY: yyyy-MM-dd / MONTH: yyyy-MM）、storeCode、memo。
     * 重复封账幂等重放（回带已封账记录，不重复审计）；未来期间拒绝（无账可封，防误操作）。
     */
    @Transactional
    public Map<String, Object> close(String periodType, String periodKey, String storeCode,
                                     String memo, String actor) {
        if (periodType == null || !PERIOD_TYPES.contains(periodType.trim().toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "期间类型 periodType 非法，允许值：DAY 日结 / MONTH 月结");
        }
        periodType = periodType.trim().toUpperCase();
        if (storeCode == null || storeCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "门店码 storeCode 不能为空（封账按门店维度）");
        }
        storeCode = storeCode.trim();
        if (!DataScope.canReadStore(storeCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "门店不存在或无权操作该门店");
        }
        // 门店存在性严格校验：封账永久不可逆，超管/集团角色数据域全域放行后仍需确认门店真实存在，
        // 防止笔误门店码静默产生永久垃圾锁行。store 服务故障时保守中止（503），不静默放行。
        try {
            if (!aggregation.storeExists(storeCode)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "门店不存在或无权操作该门店");
            }
        } catch (FinanceAggregationService.StoreLookupException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
        LocalDate periodStart = parsePeriodStart(periodType, periodKey);
        String normKey = "MONTH".equals(periodType)
                ? periodStart.withDayOfMonth(1).toString().substring(0, 7)
                : periodStart.toString();

        // 未来期间拒绝：日结不晚于今天（UTC），月结不晚于本月
        LocalDate todayUtc = OffsetDateTime.now(ZoneOffset.UTC).toLocalDate();
        LocalDate latest = "MONTH".equals(periodType) ? todayUtc.withDayOfMonth(1) : todayUtc;
        if (periodStart.isAfter(latest)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "不能对未来期间封账（" + normKey + "），请封账已结束的日/月");
        }

        SettlementPeriod existing = settlementRepo
                .findByPeriodTypeAndPeriodKeyAndStoreCode(periodType, normKey, storeCode).orElse(null);
        if (existing != null) {
            // 幂等重放：已封账，不重复落库/审计
            Map<String, Object> r = toView(existing, storeNameOf(storeCode));
            r.put("duplicated", true);
            r.put("message", "该期间已封账，幂等返回；封账后不可改账，差错请走差异调平 ADJUST");
            return r;
        }

        // 封账时点快照：期间内该店全部分录（UTC 半开区间），净额 IN 正 OUT 负
        OffsetDateTime from = periodStart.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime to = "MONTH".equals(periodType)
                ? periodStart.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()
                : periodStart.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        final String sc = storeCode;
        List<FundEntry> entries = entryRepo.findByOccurredAtBetweenOrderByOccurredAtAsc(from, to)
                .stream().filter(e -> sc.equals(e.getStoreCode())).toList();
        long net = entries.stream().mapToLong(e -> "IN".equals(e.getDirection()) ? e.getAmount() : -e.getAmount()).sum();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SettlementPeriod sp = new SettlementPeriod();
        sp.setPeriodType(periodType);
        sp.setPeriodKey(normKey);
        sp.setStoreCode(storeCode);
        sp.setStatus("CLOSED");
        sp.setEntryCount(entries.size());
        sp.setNetAmount(net);
        sp.setMemo(memo);
        sp.setClosedBy(actor != null ? actor : "system");
        sp.setClosedAt(now);
        sp.setCreatedAt(now);
        settlementRepo.save(sp);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("periodType", periodType);
        payload.put("periodKey", normKey);
        payload.put("storeCode", storeCode);
        payload.put("entryCount", entries.size());
        payload.put("netAmountFen", net);
        payload.put("memo", memo);
        audit.record("FIN_SETTLE", periodType + ":" + normKey + ":" + storeCode,
                actor != null ? actor : "system", "CLOSE", toJson(payload));

        log.info("封账完成 {} {} store={} 分录 {} 笔 净额 {} 分 by={}",
                periodType, normKey, storeCode, entries.size(), net, actor);

        Map<String, Object> r = toView(sp, storeNameOf(storeCode));
        r.put("duplicated", false);
        r.put("message", "封账成功：该期间已锁定，后续落账一律拒绝，差错请走差异调平 ADJUST");
        return r;
    }

    /**
     * 封账台账查询：GET /api/finance/settlement。
     * 可按 periodType / periodKey / storeCode 过滤；数据域逐行收敛（无权门店不返回）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String periodType, String periodKey, String storeCode) {
        Specification<SettlementPeriod> spec = DataScope.storeSpec("storeCode");
        if (periodType != null && !periodType.isBlank()) {
            String pt = periodType.trim().toUpperCase();
            if (!PERIOD_TYPES.contains(pt)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "期间类型 periodType 非法，允许值：DAY 日结 / MONTH 月结");
            }
            spec = spec.and((root, q, cb) -> cb.equal(root.get("periodType"), pt));
        }
        if (periodKey != null && !periodKey.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("periodKey"), periodKey.trim()));
        }
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode.trim()));
        }
        List<SettlementPeriod> all = settlementRepo.findAll(spec,
                Sort.by(Sort.Order.desc("periodKey"), Sort.Order.desc("closedAt")));
        List<String> codes = all.stream().map(SettlementPeriod::getStoreCode).distinct().toList();
        Map<String, String> names = codes.isEmpty() ? Map.of() : aggregation.resolveStoreNames(new ArrayList<>(codes));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SettlementPeriod sp : all) {
            rows.add(toView(sp, names.getOrDefault(sp.getStoreCode(), sp.getStoreCode())));
        }
        return rows;
    }

    /**
     * 闭期判定（FundEntryService 每条新分录落账前调用）：occurredAt 按 UTC 归一，
     * DAY 命中日结锁、MONTH 命中月结锁任一即闭期。SYS00 等系统调平分录落当前期间，不会命中历史闭期。
     */
    @Transactional(readOnly = true)
    public boolean isClosed(String storeCode, OffsetDateTime occurredAt) {
        if (storeCode == null || occurredAt == null) return false;
        LocalDate d = occurredAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        String dayKey = d.toString();
        String monthKey = d.withDayOfMonth(1).toString().substring(0, 7);
        return settlementRepo.existsByPeriodTypeAndPeriodKeyAndStoreCode("DAY", dayKey, storeCode)
                || settlementRepo.existsByPeriodTypeAndPeriodKeyAndStoreCode("MONTH", monthKey, storeCode);
    }

    // ==================== 内部工具 ====================

    private LocalDate parsePeriodStart(String periodType, String periodKey) {
        if (periodKey == null || periodKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "期间 periodKey 不能为空（日结 yyyy-MM-dd / 月结 yyyy-MM）");
        }
        String key = periodKey.trim();
        try {
            if ("MONTH".equals(periodType)) {
                // 容忍 yyyy-MM 与 yyyy-MM-dd（归一到月首）
                LocalDate d = key.length() == 7 ? LocalDate.parse(key + "-01") : LocalDate.parse(key);
                return d.withDayOfMonth(1);
            }
            return LocalDate.parse(key);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "期间 periodKey 格式非法：日结需 yyyy-MM-dd（如 2026-09-05），月结需 yyyy-MM（如 2026-09）");
        }
    }

    private String storeNameOf(String storeCode) {
        return aggregation.resolveStoreNames(List.of(storeCode)).getOrDefault(storeCode, storeCode);
    }

    private Map<String, Object> toView(SettlementPeriod sp, String storeName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settlementId", sp.getSettlementId());
        m.put("periodType", sp.getPeriodType());
        m.put("periodKey", sp.getPeriodKey());
        m.put("storeCode", sp.getStoreCode());
        m.put("storeName", storeName);
        m.put("status", sp.getStatus());
        m.put("entryCount", sp.getEntryCount());
        m.put("netAmountFen", sp.getNetAmount());
        m.put("memo", sp.getMemo());
        m.put("closedBy", sp.getClosedBy());
        m.put("closedAt", sp.getClosedAt());
        return m;
    }

    private String toJson(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }
}
