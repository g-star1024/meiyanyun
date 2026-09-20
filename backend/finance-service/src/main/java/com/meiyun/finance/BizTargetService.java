package com.meiyun.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * B49 卡8 目标管理业务服务：集团目标 → 区域/门店分解树 + 进度追踪 + 审批状态机。
 *
 * <p>写接口四件套（中文 422 校验、idem_key 幂等、FinanceAuditRecorder 全审计、状态机前置态 400）
 * 与发票/预算同轨。目标值/实际值为管理指标手工维护（非资金流水，不走「分」红线）。
 * 状态机：DRAFT --submit--> PENDING --approve--> APPROVED / --reject--> REJECTED；
 * 进度更新仅 APPROVED 可写（与前端 canEdit∧APPROVED 门控对齐）。
 */
@Service
public class BizTargetService {

    private static final Logger log = LoggerFactory.getLogger(BizTargetService.class);

    private static final Set<String> OWNER_TYPES = Set.of("GROUP", "REGION", "STORE");
    private static final Set<String> METRICS = Set.of(
            "REVENUE", "NEW_CUSTOMER", "REPURCHASE_RATE", "PROCEDURE_COUNT", "SATISFACTION");
    private static final Set<String> PERIODS = Set.of("YEAR", "QUARTER", "MONTH");

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final BizTargetRepository targetRepo;
    private final RevenueMonthlyRepository revenueMonthlyRepo;
    private final FinanceAuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${store.service.url:http://127.0.0.1:8085}")
    private String storeServiceUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public BizTargetService(BizTargetRepository targetRepo, RevenueMonthlyRepository revenueMonthlyRepo,
                            FinanceAuditRecorder audit, ObjectMapper objectMapper,
                            RestTemplate restTemplate) {
        this.targetRepo = targetRepo;
        this.revenueMonthlyRepo = revenueMonthlyRepo;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /** 目标列表（ownerType/metric/period/approval 可选过滤，按 id 升序）。 */
    public List<Map<String, Object>> list(String ownerType, String metric, String period, String approval) {
        List<BizTarget> all = targetRepo.findAllByOrderByIdAsc();
        Map<String, String> storeRegionMap = fetchStoreRegionMap();
        List<Map<String, Object>> out = new ArrayList<>();
        for (BizTarget t : all) {
            if (!DataScope.canReadTarget(t.getOwnerType(), t.getOwnerId(), t.getOwnerName())) continue;
            if (ownerType != null && !ownerType.isBlank() && !ownerType.equals(t.getOwnerType())) continue;
            if (metric != null && !metric.isBlank() && !metric.equals(t.getMetric())) continue;
            if (period != null && !period.isBlank() && !period.equals(t.getPeriod())) continue;
            if (approval != null && !approval.isBlank() && !approval.equals(t.getApproval())) continue;
            Map<String, Object> v = view(t);
            if ("REVENUE".equals(t.getMetric())) {
                v.put("aggregatedRevenue", computeAggregatedRevenue(t, storeRegionMap));
            }
            out.add(v);
        }
        return out;
    }

    /** 新建目标（默认 DRAFT，idemKey 幂等；targetId 缺省服务端生成）。 */
    @Transactional
    public Map<String, Object> create(Map<String, Object> body, String actor) {
        String idemKey = str(body.get("idemKey"));
        if (idemKey != null && !idemKey.isBlank()) {
            var dup = targetRepo.findByIdemKey(idemKey.trim());
            if (dup.isPresent()) {
                Map<String, Object> v = view(dup.get());
                v.put("duplicated", true);
                return v;
            }
        }
        String ownerType = str(body.get("ownerType"));
        if (ownerType == null || !OWNER_TYPES.contains(ownerType)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "归属层级不合法（GROUP/REGION/STORE）");
        }
        String metric = str(body.get("metric"));
        if (metric == null || !METRICS.contains(metric)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "指标不合法（REVENUE/NEW_CUSTOMER/REPURCHASE_RATE/PROCEDURE_COUNT/SATISFACTION）");
        }
        String period = str(body.get("period"));
        if (period == null || !PERIODS.contains(period)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "周期不合法（YEAR/QUARTER/MONTH）");
        }
        String ownerId = requireText(body.get("ownerId"), "归属对象");
        String ownerName = requireText(body.get("ownerName"), "归属名称");
        String periodLabel = requireText(body.get("periodLabel"), "周期标签");
        String unit = requireText(body.get("unit"), "单位");
        BigDecimal targetValue = parseDecimal(body.get("targetValue"), "目标值");
        if (targetValue == null || targetValue.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "目标值须为正数");
        }
        BigDecimal currentValue = parseDecimal(body.get("currentValue"), "当前值");
        if (currentValue == null) currentValue = BigDecimal.ZERO;
        if (currentValue.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "当前值不能为负");
        }
        int weight = parseInt(body.get("weight"), 10);
        if (weight < 0 || weight > 100) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "权重须在 0~100 之间");
        }
        String targetId = str(body.get("targetId"));
        if (targetId == null || targetId.isBlank()) {
            targetId = "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        } else {
            targetId = targetId.trim();
            if (targetRepo.findByTargetId(targetId).isPresent()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "目标编号已存在：" + targetId);
            }
        }

        BizTarget t = new BizTarget();
        t.setTargetId(truncate(targetId, 32));
        t.setOwnerId(truncate(ownerId, 32));
        t.setOwnerName(truncate(ownerName, 64));
        t.setOwnerType(ownerType);
        t.setMetric(metric);
        t.setPeriod(period);
        t.setPeriodLabel(truncate(periodLabel, 32));
        t.setTargetValue(targetValue);
        t.setCurrentValue(currentValue);
        t.setUnit(truncate(unit, 16));
        t.setWeight(weight);
        t.setApproval("DRAFT");
        t.setChildrenIds(joinChildren(body.get("children")));
        t.setIdemKey(idemKey == null || idemKey.isBlank() ? null : truncate(idemKey.trim(), 64));
        OffsetDateTime now = OffsetDateTime.now();
        t.setCreatedBy(actor);
        t.setUpdatedBy(actor);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        targetRepo.save(t);

        record(t.getTargetId(), actor, "CREATE",
                Map.of("targetId", t.getTargetId(), "ownerName", t.getOwnerName(),
                        "metric", metric, "period", period, "periodLabel", t.getPeriodLabel(),
                        "targetValue", targetValue, "unit", t.getUnit()));
        return view(t);
    }

    /** 更新当前进度（仅 APPROVED 可更新，全审计）。 */
    @Transactional
    public Map<String, Object> updateProgress(String targetId, Map<String, Object> body, String actor) {
        BizTarget t = mustTarget(targetId);
        if (!"APPROVED".equals(t.getApproval())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "仅已批准（APPROVED）目标可更新进度，当前状态：" + t.getApproval());
        }
        BigDecimal value = parseDecimal(body == null ? null : body.get("value"), "当前值");
        if (value == null || value.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "当前值须为不小于 0 的数");
        }
        BigDecimal old = t.getCurrentValue();
        t.setCurrentValue(value);
        t.setUpdatedBy(actor);
        t.setUpdatedAt(OffsetDateTime.now());
        targetRepo.save(t);
        record(t.getTargetId(), actor, "UPDATE_PROGRESS",
                Map.of("targetId", t.getTargetId(), "oldValue", old, "newValue", value, "unit", t.getUnit()));
        return view(t);
    }

    /** 提交审批：DRAFT → PENDING。 */
    @Transactional
    public Map<String, Object> submit(String targetId, String actor) {
        BizTarget t = mustTarget(targetId);
        if (!"DRAFT".equals(t.getApproval())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "仅草稿（DRAFT）目标可提交审批，当前状态：" + t.getApproval());
        }
        t.setApproval("PENDING");
        t.setSubmittedBy(actor);
        t.setSubmittedAt(OffsetDateTime.now());
        t.setUpdatedBy(actor);
        t.setUpdatedAt(OffsetDateTime.now());
        targetRepo.save(t);
        record(t.getTargetId(), actor, "SUBMIT",
                Map.of("targetId", t.getTargetId(), "ownerName", t.getOwnerName()));
        return view(t);
    }

    /** 批准：PENDING → APPROVED（target:approve）。 */
    @Transactional
    public Map<String, Object> approve(String targetId, String actor) {
        BizTarget t = mustTarget(targetId);
        if (!"PENDING".equals(t.getApproval())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "仅待审批（PENDING）目标可批准，当前状态：" + t.getApproval());
        }
        t.setApproval("APPROVED");
        t.setApprovedBy(actor);
        t.setApprovedAt(OffsetDateTime.now());
        t.setRejectReason(null);
        t.setUpdatedBy(actor);
        t.setUpdatedAt(OffsetDateTime.now());
        targetRepo.save(t);
        record(t.getTargetId(), actor, "APPROVE",
                Map.of("targetId", t.getTargetId(), "ownerName", t.getOwnerName()));
        return view(t);
    }

    /** 驳回：PENDING → REJECTED（target:approve，reason 必填）。 */
    @Transactional
    public Map<String, Object> reject(String targetId, String reason, String actor) {
        BizTarget t = mustTarget(targetId);
        if (!"PENDING".equals(t.getApproval())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "仅待审批（PENDING）目标可驳回，当前状态：" + t.getApproval());
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "驳回原因不能为空");
        }
        t.setApproval("REJECTED");
        t.setApprovedBy(actor);
        t.setApprovedAt(OffsetDateTime.now());
        t.setRejectReason(truncate(reason.trim(), 256));
        t.setUpdatedBy(actor);
        t.setUpdatedAt(OffsetDateTime.now());
        targetRepo.save(t);
        record(t.getTargetId(), actor, "REJECT",
                Map.of("targetId", t.getTargetId(), "ownerName", t.getOwnerName(), "reason", reason.trim()));
        return view(t);
    }

    /** B69 卡1（L133）：退回修改——REJECTED → DRAFT（target:approve，保留 rejectReason 审计溯源）。 */
    @Transactional
    public Map<String, Object> reset(String targetId, String actor) {
        BizTarget t = mustTarget(targetId);
        if (!"REJECTED".equals(t.getApproval())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "仅已驳回（REJECTED）目标可退回修改，当前状态：" + t.getApproval());
        }
        String keptReason = t.getRejectReason();
        t.setApproval("DRAFT");
        t.setSubmittedBy(null);
        t.setSubmittedAt(null);
        t.setApprovedBy(null);
        t.setApprovedAt(null);
        t.setUpdatedBy(actor);
        t.setUpdatedAt(OffsetDateTime.now());
        targetRepo.save(t);
        record(t.getTargetId(), actor, "RESET",
                Map.of("targetId", t.getTargetId(), "actor", actor,
                        "previousRejectReason", keptReason == null ? "" : keptReason));
        return view(t);
    }

    // ==================== 内部 ====================

    /** 前端 TargetLine 视图契约：id 为可读业务 id，children 为业务 id 数组。 */
    private Map<String, Object> view(BizTarget t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", t.getTargetId());
        v.put("ownerId", t.getOwnerId());
        v.put("ownerName", t.getOwnerName());
        v.put("ownerType", t.getOwnerType());
        v.put("metric", t.getMetric());
        v.put("period", t.getPeriod());
        v.put("periodLabel", t.getPeriodLabel());
        v.put("targetValue", t.getTargetValue());
        v.put("currentValue", t.getCurrentValue());
        v.put("unit", t.getUnit());
        v.put("weight", t.getWeight());
        v.put("approval", t.getApproval());
        v.put("children", splitChildren(t.getChildrenIds()));
        v.put("submittedBy", t.getSubmittedBy());
        v.put("approvedBy", t.getApprovedBy());
        v.put("rejectReason", t.getRejectReason());
        v.put("aggregatedRevenue", null);
        return v;
    }

    private BizTarget mustTarget(String targetId) {
        return targetRepo.findByTargetId(targetId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "目标不存在：" + targetId));
    }

    private static List<String> splitChildren(String childrenIds) {
        List<String> out = new ArrayList<>();
        if (childrenIds == null || childrenIds.isBlank()) return out;
        for (String s : childrenIds.split(",")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String joinChildren(Object children) {
        if (children == null) return null;
        if (!(children instanceof List)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "children 须为子目标编号数组");
        }
        List<String> parts = new ArrayList<>();
        for (Object o : (List<Object>) children) {
            String s = str(o);
            if (s != null && !s.isBlank()) parts.add(s.trim());
        }
        String joined = String.join(",", parts);
        return truncate(joined.isEmpty() ? null : joined, 256);
    }

    private void record(String txnNo, String actor, String action, Object payload) {
        try {
            audit.record("BIZ_TARGET", txnNo, actor == null ? "system" : actor, action,
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("审计序列化失败 bizType=BIZ_TARGET txnNo={} : {}", txnNo, e.getMessage());
        }
    }

    private static String requireText(Object o, String label) {
        String s = str(o);
        if (s == null || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "不能为空");
        }
        return s.trim();
    }

    private static BigDecimal parseDecimal(Object o, String label) {
        if (o == null) return null;
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "格式不合法：" + o);
        }
    }

    private static int parseInt(Object o, int dft) {
        if (o == null) return dft;
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "权重格式不合法：" + o);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private BigDecimal computeAggregatedRevenue(BizTarget t, Map<String, String> storeRegionMap) {
        LocalDate[] range = parsePeriodRange(t.getPeriodLabel());
        if (range == null) return null;
        List<String> storeCodes = resolveStoreCodes(t, storeRegionMap);
        if (storeCodes.isEmpty()) return BigDecimal.ZERO;
        long totalCents = revenueMonthlyRepo
                .findByStoreCodeInAndPeriodMonthBetween(storeCodes, range[0], range[1])
                .stream().mapToLong(r -> r.getRevenue() == null ? 0L : r.getRevenue()).sum();
        return BigDecimal.valueOf(totalCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private LocalDate[] parsePeriodRange(String label) {
        if (label == null || label.isBlank()) return null;
        try {
            if (label.endsWith("年度")) {
                int y = Integer.parseInt(label.substring(0, 4));
                return new LocalDate[] { LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 1) };
            }
            if (label.contains("-Q")) {
                int y = Integer.parseInt(label.substring(0, 4));
                int q = Integer.parseInt(label.substring(label.indexOf('Q') + 1));
                int sm = (q - 1) * 3 + 1;
                return new LocalDate[] { LocalDate.of(y, sm, 1), LocalDate.of(y, sm + 2, 1) };
            }
            String[] p = label.split("-");
            if (p.length == 2) {
                LocalDate m = LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), 1);
                return new LocalDate[] { m, m };
            }
        } catch (Exception e) {
            log.warn("期间解析失败 periodLabel={} : {}", label, e.getMessage());
        }
        return null;
    }

    private List<String> resolveStoreCodes(BizTarget t, Map<String, String> storeRegionMap) {
        if ("STORE".equals(t.getOwnerType())) return List.of(t.getOwnerId());
        if ("GROUP".equals(t.getOwnerType())) return new ArrayList<>(storeRegionMap.keySet());
        if ("REGION".equals(t.getOwnerType())) {
            return storeRegionMap.entrySet().stream()
                    .filter(e -> {
                        String r = e.getValue();
                        return r != null && (r.contains(t.getOwnerName()) || t.getOwnerName().contains(r));
                    })
                    .map(Map.Entry::getKey).collect(Collectors.toList());
        }
        return List.of();
    }

    private Map<String, String> fetchStoreRegionMap() {
        try {
            HttpHeaders h = new HttpHeaders();
            h.set("X-Internal-Token", internalToken);
            ResponseEntity<List<Map<String, Object>>> resp = restTemplate.exchange(
                    storeServiceUrl + "/api/stores", HttpMethod.GET,
                    new HttpEntity<>(h), LIST_MAP_TYPE);
            Map<String, String> out = new LinkedHashMap<>();
            if (resp.getBody() != null) {
                for (Map<String, Object> s : resp.getBody()) {
                    String code = str(s.get("storeCode"));
                    String region = str(s.get("region"));
                    if (code != null) out.put(code, region == null ? "" : region);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("门店区域映射获取失败（回落空映射）: {}", e.getMessage());
            return Map.of();
        }
    }
}
