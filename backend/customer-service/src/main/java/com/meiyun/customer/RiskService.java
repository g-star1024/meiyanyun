package com.meiyun.customer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.customer.CustomerService.BadReq;
import com.meiyun.customer.CustomerService.NotFound;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 黑名单与风控服务（M3-B6 / DESIGN-M3 §3 M3-17）。
 * 状态机照前端 mock 活规格：提交拉黑→PENDING_REVIEW（HIGH 级别默认拦截交易）；
 * 审核通过→BLACKLISTED 且拦截交易、记处置时刻/处置人；审核驳回（原因必填）→WATCHING；
 * 解除风险（原因必填）BLACKLISTED|WATCHING→RELEASED 并记处置时刻/处置人。
 * 时间线 JSONB 逐步追加 [{action,by,at,comment?}]（at 为 ISO 串，前端 fmtDate 直接 new Date 解析）；
 * 全程落 RISK 审计（SUBMIT/APPROVE/REJECT/RELEASE/RULE_TOGGLE），操作人取 DataScope.currentActor()。
 */
@Service
public class RiskService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> LEVELS = Set.of(
            RiskRecord.LEVEL_HIGH, RiskRecord.LEVEL_MEDIUM, RiskRecord.LEVEL_LOW);
    private static final Set<String> REASONS = Set.of(
            RiskRecord.REASON_FRAUD, RiskRecord.REASON_CHARGEBACK, RiskRecord.REASON_MALICIOUS_COMPLAINT,
            RiskRecord.REASON_ILLEGAL_PRACTICE, RiskRecord.REASON_OTHER);

    private final RiskRecordRepository riskRecordRepository;
    private final RiskRuleRepository riskRuleRepository;
    private final AuditRecorder audit;

    public RiskService(RiskRecordRepository riskRecordRepository, RiskRuleRepository riskRuleRepository,
                       AuditRecorder audit) {
        this.riskRecordRepository = riskRecordRepository;
        this.riskRuleRepository = riskRuleRepository;
        this.audit = audit;
    }

    /** 时间线条目（与前端 {action,by,at,comment?} 契约一致；comment 为空序列化为 null）。 */
    public record TimelineItem(String action, String by, String at, String comment) {}

    /** 名单视图（createdAt/resolvedAt 为 ISO 串或 null；customerId 空转 ""）。 */
    public record RiskRecordView(
            Long id, String riskNo, String customerId, String customerName, String phoneMask,
            String level, String reason, String reasonDetail, String status, Integer hitCount,
            Boolean blockTransactions, String operator, String createdAt, String resolvedAt,
            String resolvedBy, List<TimelineItem> timeline) {}

    /** 规则视图（ruleNo 透出供展示；前端以数值 id 为主键）。 */
    public record RiskRuleView(
            Long id, String ruleNo, String name, String description, Boolean enabled,
            String action, Integer hitCount) {}

    /** 名单列表：命中次数倒序（与前端 filtered 排序口径一致）。 */
    @Transactional(readOnly = true)
    public List<RiskRecordView> listRecords() {
        return riskRecordRepository.findAllByOrderByHitCountDesc().stream().map(this::toView).toList();
    }

    /** 规则列表：按 id 升序（种子 RR-1..RR-5 序）。 */
    @Transactional(readOnly = true)
    public List<RiskRuleView> listRules() {
        return riskRuleRepository.findAllByOrderByIdAsc().stream().map(this::toRuleView).toList();
    }

    /** 提交拉黑审核：姓名/详细说明必填，级别/类型词表校验；HIGH 级别默认拦截交易；落 SUBMIT 审计。 */
    @Transactional
    public RiskRecordView submit(String customerName, String phoneMask, String level, String reason, String detail) {
        if (customerName == null || customerName.isBlank()) {
            throw new BadReq("客户姓名不能为空");
        }
        if (detail == null || detail.isBlank()) {
            throw new BadReq("请填写详细说明");
        }
        if (level == null || !LEVELS.contains(level)) {
            throw new BadReq("风险级别不合法，仅支持 HIGH/MEDIUM/LOW");
        }
        if (reason == null || !REASONS.contains(reason)) {
            throw new BadReq("风险类型不合法");
        }
        String actor = DataScope.currentActor();
        OffsetDateTime now = OffsetDateTime.now();
        RiskRecord r = new RiskRecord();
        r.setRiskNo(nextRiskNo());
        r.setCustomerName(customerName.trim());
        r.setPhoneMask(phoneMask == null || phoneMask.isBlank() ? "" : phoneMask.trim());
        r.setLevel(level);
        r.setReason(reason);
        r.setReasonDetail(detail.trim());
        r.setStatus(RiskRecord.STATUS_PENDING_REVIEW);
        r.setHitCount(1);
        r.setBlockTransactions(RiskRecord.LEVEL_HIGH.equals(level));
        r.setOperator(actor);
        r.setStoreCode(ownStoreOrNull());
        r.setTimeline(toJson(List.of(tl("提交拉黑审核", actor, now, detail.trim()))));
        RiskRecord saved = riskRecordRepository.save(r);
        audit.record("RISK", saved.getRiskNo(), actor, "SUBMIT",
                "{\"riskNo\":\"" + esc(saved.getRiskNo()) + "\",\"customer\":\"" + esc(saved.getCustomerName())
                        + "\",\"level\":\"" + level + "\",\"reason\":\"" + reason + "\"}");
        return toView(saved);
    }

    /** 审核通过：PENDING_REVIEW→BLACKLISTED，拦截交易并记处置时刻/处置人；落 APPROVE 审计。 */
    @Transactional
    public RiskRecordView approve(Long id) {
        RiskRecord r = mustGet(id);
        if (!RiskRecord.STATUS_PENDING_REVIEW.equals(r.getStatus())) {
            throw new BadReq("仅待审核记录可执行审核通过，当前状态：" + statusLabel(r.getStatus()));
        }
        String actor = DataScope.currentActor();
        OffsetDateTime now = OffsetDateTime.now();
        r.setStatus(RiskRecord.STATUS_BLACKLISTED);
        r.setBlockTransactions(true);
        r.setResolvedAt(now);
        r.setResolvedBy(actor);
        r.setTimeline(appendTl(r.getTimeline(), tl("审核通过，加入黑名单", actor, now, null)));
        RiskRecord saved = riskRecordRepository.save(r);
        audit.record("RISK", saved.getRiskNo(), actor, "APPROVE",
                "{\"riskNo\":\"" + esc(saved.getRiskNo()) + "\",\"customer\":\"" + esc(saved.getCustomerName())
                        + "\",\"blockTransactions\":true}");
        return toView(saved);
    }

    /** 审核驳回：PENDING_REVIEW→WATCHING，原因必填；落 REJECT 审计。 */
    @Transactional
    public RiskRecordView reject(Long id, String reason) {
        RiskRecord r = mustGet(id);
        if (!RiskRecord.STATUS_PENDING_REVIEW.equals(r.getStatus())) {
            throw new BadReq("仅待审核记录可驳回，当前状态：" + statusLabel(r.getStatus()));
        }
        if (reason == null || reason.isBlank()) {
            throw new BadReq("驳回原因不能为空");
        }
        String actor = DataScope.currentActor();
        OffsetDateTime now = OffsetDateTime.now();
        r.setStatus(RiskRecord.STATUS_WATCHING);
        r.setBlockTransactions(false);
        r.setTimeline(appendTl(r.getTimeline(), tl("审核驳回，转观察", actor, now, reason.trim())));
        RiskRecord saved = riskRecordRepository.save(r);
        audit.record("RISK", saved.getRiskNo(), actor, "REJECT",
                "{\"riskNo\":\"" + esc(saved.getRiskNo()) + "\",\"customer\":\"" + esc(saved.getCustomerName())
                        + "\",\"reason\":\"" + esc(reason.trim()) + "\"}");
        return toView(saved);
    }

    /** 解除风险：BLACKLISTED|WATCHING→RELEASED，原因必填，记处置时刻/处置人；落 RELEASE 审计。 */
    @Transactional
    public RiskRecordView release(Long id, String reason) {
        RiskRecord r = mustGet(id);
        if (!RiskRecord.STATUS_BLACKLISTED.equals(r.getStatus())
                && !RiskRecord.STATUS_WATCHING.equals(r.getStatus())) {
            throw new BadReq("仅已拉黑或观察中记录可解除，当前状态：" + statusLabel(r.getStatus()));
        }
        if (reason == null || reason.isBlank()) {
            throw new BadReq("解除原因不能为空");
        }
        String actor = DataScope.currentActor();
        OffsetDateTime now = OffsetDateTime.now();
        r.setStatus(RiskRecord.STATUS_RELEASED);
        r.setBlockTransactions(false);
        r.setResolvedAt(now);
        r.setResolvedBy(actor);
        r.setTimeline(appendTl(r.getTimeline(), tl("解除风险", actor, now, reason.trim())));
        RiskRecord saved = riskRecordRepository.save(r);
        audit.record("RISK", saved.getRiskNo(), actor, "RELEASE",
                "{\"riskNo\":\"" + esc(saved.getRiskNo()) + "\",\"customer\":\"" + esc(saved.getCustomerName())
                        + "\",\"reason\":\"" + esc(reason.trim()) + "\"}");
        return toView(saved);
    }

    /** 规则启停开关：enabled 翻转；落 RULE_TOGGLE 审计。 */
    @Transactional
    public RiskRuleView toggleRule(Long id) {
        RiskRule rule = riskRuleRepository.findById(id)
                .orElseThrow(() -> new NotFound("风控规则不存在"));
        rule.setEnabled(!Boolean.TRUE.equals(rule.getEnabled()));
        RiskRule saved = riskRuleRepository.save(rule);
        audit.record("RISK", saved.getRuleNo(), DataScope.currentActor(), "RULE_TOGGLE",
                "{\"ruleNo\":\"" + esc(saved.getRuleNo()) + "\",\"name\":\"" + esc(saved.getName())
                        + "\",\"enabled\":" + saved.getEnabled() + "}");
        return toRuleView(saved);
    }

    private RiskRecord mustGet(Long id) {
        return riskRecordRepository.findById(id).orElseThrow(() -> new NotFound("风控记录不存在"));
    }

    /** 当日单号：RK+yyyyMMdd-6 位序号，取 DB 当日最大号+1（与 IoTaskService.nextTaskNo 同构）。 */
    private synchronized String nextRiskNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "RK" + day + "-";
        int seq = 1;
        var max = riskRecordRepository.findTopByRiskNoLikeOrderByRiskNoDesc(prefix + "%");
        if (max.isPresent()) {
            String tail = max.get().getRiskNo().substring(prefix.length());
            seq = Integer.parseInt(tail) + 1;
        }
        return prefix + String.format("%06d", seq);
    }

    private static Map<String, Object> tl(String action, String by, OffsetDateTime at, String comment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("by", by);
        m.put("at", at.toString());
        if (comment != null && !comment.isBlank()) {
            m.put("comment", comment);
        }
        return m;
    }

    private String appendTl(String json, Map<String, Object> item) {
        List<Map<String, Object>> list = readTl(json);
        list.add(item);
        return toJson(list);
    }

    private static List<Map<String, Object>> readTl(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private RiskRecordView toView(RiskRecord r) {
        List<TimelineItem> timeline = readTl(r.getTimeline()).stream()
                .map(m -> new TimelineItem(str(m.get("action")), str(m.get("by")), str(m.get("at")),
                        m.get("comment") == null ? null : m.get("comment").toString()))
                .toList();
        return new RiskRecordView(
                r.getId(),
                r.getRiskNo(),
                r.getCustomerId() == null ? "" : r.getCustomerId(),
                r.getCustomerName(),
                r.getPhoneMask(),
                r.getLevel(),
                r.getReason(),
                r.getReasonDetail(),
                r.getStatus(),
                r.getHitCount(),
                r.getBlockTransactions(),
                r.getOperator(),
                r.getCreatedAt() == null ? "" : r.getCreatedAt().toString(),
                r.getResolvedAt() == null ? null : r.getResolvedAt().toString(),
                r.getResolvedBy(),
                timeline);
    }

    private RiskRuleView toRuleView(RiskRule r) {
        return new RiskRuleView(
                r.getId(), r.getRuleNo(), r.getName(), r.getDescription(),
                r.getEnabled(), r.getAction(), r.getHitCount());
    }

    private static String ownStoreOrNull() {
        LoginUser u = DataScope.current();
        return u == null ? null : u.storeCode();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 状态中文标签（4xx 报错透出当前态，便于前端/运维直读）。 */
    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case RiskRecord.STATUS_BLACKLISTED -> "已拉黑";
            case RiskRecord.STATUS_WATCHING -> "观察中";
            case RiskRecord.STATUS_RELEASED -> "已解除";
            case RiskRecord.STATUS_PENDING_REVIEW -> "待审核";
            default -> status == null ? "未知" : status;
        };
    }
}
