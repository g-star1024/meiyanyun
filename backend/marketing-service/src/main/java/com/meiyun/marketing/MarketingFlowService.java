package com.meiyun.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 营销自动化 Flow 引擎（P5-B90，B69 定案：内建轻量 Trigger-Condition-Action，不引 LiteFlow/Drools）。
 *
 * <p>定时任务 {@link MarketingFlowJob} 每 5 分钟驱动一轮：扫启用中规则，按 trigger_type 解析候选客户，
 * 命中即按 action_type 生成关怀任务 / 复诊召回。
 *
 * <p>三 Trigger（业务时区 +8「今日」为锚，沿 FollowupSopDueJob BIZ_TZ 惯例）：
 * <ul>
 *   <li>BIRTHDAY：customer 域 birthday-on 投影回当日生日客户（v1 仅支持 daysBefore=0）；</li>
 *   <li>DORMANT_DAYS：txn 已收款订单窗口 [今日-N-365, 今日] 按客户聚合末次消费日，
 *       精确命中 = 末次消费日恰好满 N 天（天然不重复）；</li>
 *   <li>VISIT_GAP_DAYS：同窗口聚合，宽命中 = 末次到店已满 N 天；CREATE_RECALL 动作靠
 *       「同规则同客户在途单（PENDING/NOTIFIED/CONFIRMED）不重复生成」收敛，防每日刷单。</li>
 * </ul>
 *
 * <p>幂等防重：idem_key={ruleNo}:{customerId}:{triggerDate} 唯一约束（automation_log.uk），
 * 查重命中静默跳过不落日志；SUCCESS/SKIPPED/FAILED 均落 idem 键（当日不重试，次日 triggerDate
 * 变化自然重试）；依赖域（txn/customer）故障时不落任何 idem，整规则中止、下轮（5 分钟后）自愈
 * （沿 AutoGrantService「不推进游标」语义）。
 *
 * <p>姓名/门店解析：BIRTHDAY 候选自带；DORMANT/VISIT_GAP 候选仅有订单投影的 customerId/storeCode，
 * 经 {@link CustomerFlowClient#fetchCustomer} 软降级解析（404→SKIPPED「客户不存在」，域故障→整规则中止）。
 *
 * <p>事务边界复刻 AutoGrantService：scan 无类级大事务，单客户 try-catch 独立落库提交，
 * 单笔失败只落 FAILED 日志不毒化整批。诊疗边界 D5：txn 零改动、表零共享、仅内部只读投影。
 */
@Service
public class MarketingFlowService {

    private static final Logger log = LoggerFactory.getLogger(MarketingFlowService.class);

    /** 业务时区（DESIGN §4「今日（+8）」，沿 FollowupSopDueJob 惯例）。 */
    private static final ZoneOffset BIZ_TZ = ZoneOffset.of("+08:00");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> CARE_TYPES = Set.of("BIRTHDAY", "HOLIDAY", "REPURCHASE", "REACTIVATE");
    private static final Set<String> CARE_CHANNELS = Set.of("SMS", "WECHAT", "PHONE");
    private static final Set<String> RECALL_METHODS = Set.of("PHONE", "WECHAT", "SMS", "IN_STORE");
    /** 召回在途状态（未闭环）：存在即不重复生成。 */
    private static final List<String> RECALL_OPEN_STATUSES = List.of("PENDING", "NOTIFIED", "CONFIRMED");

    private final AutomationRuleRepository ruleRepo;
    private final AutomationLogRepository logRepo;
    private final CareTaskRepository careRepo;
    private final RecallRepository recallRepo;
    private final CustomerFlowClient customerClient;
    private final TxnInternalClient txnClient;
    private final BizNoGenerator bizNo;
    private final ForbiddenWordService forbiddenWordService;

    public MarketingFlowService(AutomationRuleRepository ruleRepo, AutomationLogRepository logRepo,
                                CareTaskRepository careRepo, RecallRepository recallRepo,
                                CustomerFlowClient customerClient, TxnInternalClient txnClient,
                                BizNoGenerator bizNo, ForbiddenWordService forbiddenWordService) {
        this.ruleRepo = ruleRepo;
        this.logRepo = logRepo;
        this.careRepo = careRepo;
        this.recallRepo = recallRepo;
        this.customerClient = customerClient;
        this.txnClient = txnClient;
        this.bizNo = bizNo;
        this.forbiddenWordService = forbiddenWordService;
    }

    /** 单轮扫描结果。error 非空表示扫描本身异常（规则级故障不计入，仅告警下轮自愈）。 */
    public record ScanResult(long scanned, long created, long skipped, long failed, String error) {}

    /** 候选客户（lastVisitDate 仅 DORMANT/VISIT_GAP 解析有值；name 为 null 时动作执行前软解析）。 */
    private record FlowCandidate(String customerId, String name, String storeCode, String level,
                                 LocalDate lastVisitDate) {}

    /** 单候选执行产出：status=SUCCESS/SKIPPED/FAILED，actionRef=产物单号（care_no/recall_no）。 */
    private record Outcome(String status, String actionRef, String message) {}

    /** 规则级可变累加器（域故障中断时已处理计数保留）。 */
    private static class Acc {
        long scanned;
        long created;
        long skipped;
        long failed;
    }

    /**
     * 执行一轮 Flow 扫描：遍历启用规则逐条解析候选并执行动作。
     * 单规则依赖域故障只中止该规则（下轮自愈），不影响其余规则。
     */
    public ScanResult scan() {
        LocalDate today = LocalDate.now(BIZ_TZ);
        Acc total = new Acc();
        try {
            for (AutomationRule rule : ruleRepo.findByEnabledTrueOrderByCreatedAtDesc()) {
                try {
                    scanRule(rule, today, total);
                } catch (TxnServiceUnavailableException | CustomerServiceUnavailableException ex) {
                    // 依赖域故障：本规则中止，不落 idem（下轮 5 分钟后自愈），其余规则照常
                    log.warn("Flow 规则 {} 本轮中止（依赖域故障，下轮自愈）：{}", rule.getRuleNo(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.error("营销 Flow 扫描异常", ex);
            return new ScanResult(total.scanned, total.created, total.skipped, total.failed,
                    "扫描异常：" + ex.getMessage());
        }
        return new ScanResult(total.scanned, total.created, total.skipped, total.failed, null);
    }

    /** 扫描单条规则：解析候选 → 逐候选 idem 查重 → 执行动作 → 落执行日志。 */
    private void scanRule(AutomationRule rule, LocalDate today, Acc acc) {
        List<FlowCandidate> candidates = resolveCandidates(rule, today);
        for (FlowCandidate c : candidates) {
            acc.scanned++;
            String idemKey = rule.getRuleNo() + ":" + c.customerId() + ":" + today;
            if (logRepo.existsByIdemKey(idemKey)) {
                // 邻轮重叠/当日已处理：静默跳过不落重复日志
                acc.skipped++;
                continue;
            }
            try {
                Outcome o = execute(rule, c, today);
                saveLog(rule, c, today, o, idemKey);
                switch (o.status()) {
                    case "SUCCESS" -> acc.created++;
                    case "SKIPPED" -> acc.skipped++;
                    default -> acc.failed++;
                }
            } catch (TxnServiceUnavailableException | CustomerServiceUnavailableException ex) {
                // 域故障：当前候选落 FAILED 留痕（当日占位，次日重试），随后中断本规则，
                // 未处理候选不落 idem → 下轮（5 分钟后）自愈（DESIGN D4/§4）
                log.warn("Flow 规则 {} 客户 {} 依赖域故障，本规则中断：{}",
                        rule.getRuleNo(), c.customerId(), ex.getMessage());
                saveLog(rule, c, today, new Outcome("FAILED", null, "依赖域故障，本规则中断：" + ex.getMessage()), idemKey);
                acc.failed++;
                throw ex;
            } catch (Exception ex) {
                acc.failed++;
                log.warn("Flow 规则 {} 客户 {} 执行异常：{}", rule.getRuleNo(), c.customerId(), ex.getMessage());
                saveLog(rule, c, today, new Outcome("FAILED", null, "执行异常：" + ex.getMessage()), idemKey);
            }
        }
    }

    /** 按 trigger_type 解析候选客户清单（配置非法/不支持返回空清单并告警）。 */
    private List<FlowCandidate> resolveCandidates(AutomationRule rule, LocalDate today) {
        JsonNode trig = readJson(rule.getTriggerConfig());
        switch (rule.getTriggerType()) {
            case "BIRTHDAY" -> {
                int daysBefore = trig == null ? 0 : trig.path("daysBefore").asInt(0);
                if (daysBefore != 0) {
                    log.warn("Flow 规则 {} daysBefore={}：v1 仅支持 daysBefore=0，本轮跳过",
                            rule.getRuleNo(), daysBefore);
                    return List.of();
                }
                return customerClient.fetchBirthdayOn(today.getMonthValue(), today.getDayOfMonth()).stream()
                        .filter(b -> b.customerId() != null && !b.customerId().isBlank())
                        .map(b -> new FlowCandidate(b.customerId(), b.name(), b.storeCode(), b.level(), null))
                        .toList();
            }
            case "DORMANT_DAYS" -> {
                int n = trig == null ? 0 : trig.path("dormantDays").asInt(0);
                if (n <= 0) {
                    log.warn("Flow 规则 {} dormantDays={} 非法，本轮跳过", rule.getRuleNo(), n);
                    return List.of();
                }
                LocalDate threshold = today.minusDays(n);
                return lastVisitCandidates(today, n).stream()
                        .filter(c -> c.lastVisitDate().isEqual(threshold))
                        .toList();
            }
            case "VISIT_GAP_DAYS" -> {
                int n = trig == null ? 0 : trig.path("gapDays").asInt(0);
                if (n <= 0) {
                    log.warn("Flow 规则 {} gapDays={} 非法，本轮跳过", rule.getRuleNo(), n);
                    return List.of();
                }
                LocalDate threshold = today.minusDays(n);
                return lastVisitCandidates(today, n).stream()
                        .filter(c -> !c.lastVisitDate().isAfter(threshold))
                        .toList();
            }
            default -> {
                log.warn("Flow 规则 {} trigger_type={} 未知，本轮跳过", rule.getRuleNo(), rule.getTriggerType());
                return List.of();
            }
        }
    }

    /**
     * DORMANT/VISIT_GAP 共用候选源：拉 txn 已收款订单窗口 [今日-N-365, 今日]，
     * 按客户聚合末次消费日（业务时区 +8）与末次消费门店；姓名留空由动作执行前软解析。
     */
    private List<FlowCandidate> lastVisitCandidates(LocalDate today, int days) {
        String from = today.minusDays(days).minusDays(365).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String to = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        Map<String, List<TxnInternalClient.PaidOrder>> byCustomer = txnClient.fetchPaidOrders(from, to).stream()
                .filter(o -> o.customerId() != null && !o.customerId().isBlank())
                .filter(o -> o.createdAt() != null)
                .collect(Collectors.groupingBy(TxnInternalClient.PaidOrder::customerId));
        List<FlowCandidate> out = new ArrayList<>();
        for (Map.Entry<String, List<TxnInternalClient.PaidOrder>> e : byCustomer.entrySet()) {
            TxnInternalClient.PaidOrder latest = e.getValue().stream()
                    .max(Comparator.comparing(TxnInternalClient.PaidOrder::createdAt))
                    .orElse(null);
            if (latest == null) continue;
            LocalDate last = latest.createdAt().withOffsetSameInstant(BIZ_TZ).toLocalDate();
            out.add(new FlowCandidate(e.getKey(), null, latest.storeCode(), null, last));
        }
        return out;
    }

    /**
     * 执行单候选动作：CREATE_RECALL 在途防重 → 姓名软解析（BIRTHDAY 候选自带 name 跳过）→
     * 门店过滤 → action_config 解析 → 落产物单。
     */
    private Outcome execute(AutomationRule rule, FlowCandidate c, LocalDate today) {
        // CREATE_RECALL 在途防重（宽命中 trigger 收敛，防每日刷单）
        if ("CREATE_RECALL".equals(rule.getActionType())
                && recallRepo.existsByRuleNoAndCustomerIdAndStatusIn(
                        rule.getRuleNo(), c.customerId(), RECALL_OPEN_STATUSES)) {
            return new Outcome("SKIPPED", null, "同规则存在在途召回单，不重复生成");
        }
        // 姓名/门店软解析（404→SKIPPED；域故障→CustomerServiceUnavailableException 整规则中止）
        String name = c.name();
        String storeCode = c.storeCode();
        if (name == null || name.isBlank()) {
            CustomerFlowClient.CustomerBrief brief = customerClient.fetchCustomer(c.customerId());
            if (brief == null) {
                return new Outcome("SKIPPED", null, "客户不存在（已合并或已删除）");
            }
            name = brief.name();
            if (storeCode == null || storeCode.isBlank()) {
                storeCode = brief.storeCode();
            }
        }
        // 门店过滤（铁律-1-D：规则 store_code 非空=按客户归属门店限定）
        if (!storeMatches(rule.getStoreCode(), storeCode)) {
            return new Outcome("SKIPPED", null, "门店不匹配规则限定");
        }
        JsonNode cfg = readJson(rule.getActionConfig());
        return switch (rule.getActionType()) {
            case "CREATE_CARE_TASK" -> createCareTask(rule, c, today, name, storeCode, cfg);
            case "CREATE_RECALL" -> createRecall(rule, c, today, name, storeCode, cfg);
            default -> new Outcome("FAILED", null, "未知 action_type：" + rule.getActionType());
        };
    }

    /** 生成关怀任务（PENDING 待关怀，planDate=今日，由运营在 /m3-care 执行 send）。 */
    private Outcome createCareTask(AutomationRule rule, FlowCandidate c, LocalDate today,
                                   String name, String storeCode, JsonNode cfg) {
        String careType = textOr(cfg, "careType", "REACTIVATE");
        String channel = textOr(cfg, "channel", "SMS");
        String content = textOr(cfg, "contentTemplate", "");
        if (!CARE_TYPES.contains(careType)) {
            return new Outcome("FAILED", null, "careType 非法：" + careType);
        }
        if (!CARE_CHANNELS.contains(channel)) {
            return new Outcome("FAILED", null, "channel 非法：" + channel);
        }
        if (content.length() > 500) {
            return new Outcome("FAILED", null, "contentTemplate 超长（>500 字）");
        }
        // DESIGN §4 执行流：content 经违禁词校验（模板为受控文案，命中=配置事故落 FAILED 告警）
        List<String> hits = forbiddenWordService.check(content);
        if (!hits.isEmpty()) {
            return new Outcome("FAILED", null, "内容命中违禁词：" + String.join("; ", hits));
        }
        CareTask t = new CareTask();
        t.setCareNo(bizNo.next("CARE", like -> careRepo.findTopByCareNoLikeOrderByCareNoDesc(like)
                .map(CareTask::getCareNo).orElse(null)));
        t.setCustomerId(c.customerId());
        t.setCustomerName(name);
        t.setType(careType);
        t.setChannel(channel);
        t.setContent(content.replace("{name}", name == null ? "" : name));
        t.setPlanDate(today);
        t.setRuleNo(rule.getRuleNo());
        t.setStoreCode(storeCode);
        t.setCreatedBy("SYSTEM");
        careRepo.save(t);
        return new Outcome("SUCCESS", t.getCareNo(), "生成关怀任务");
    }

    /** 生成复诊召回（PENDING，source=SYSTEM_AUTO，dueDate=今日，timeline 首条「创建复诊提醒」）。 */
    private Outcome createRecall(AutomationRule rule, FlowCandidate c, LocalDate today,
                                 String name, String storeCode, JsonNode cfg) {
        String method = textOr(cfg, "method", "WECHAT");
        String reason = textOr(cfg, "reason", "疗程复诊提醒");
        if (!RECALL_METHODS.contains(method)) {
            return new Outcome("FAILED", null, "method 非法：" + method);
        }
        if (reason.length() > 200) {
            return new Outcome("FAILED", null, "reason 超长（>200 字）");
        }
        Recall r = new Recall();
        r.setRecallNo(bizNo.next("RC", like -> recallRepo.findTopByRecallNoLikeOrderByRecallNoDesc(like)
                .map(Recall::getRecallNo).orElse(null)));
        r.setCustomerId(c.customerId());
        r.setCustomerName(name);
        r.setSource("SYSTEM_AUTO");
        r.setReason(reason);
        r.setLastVisitDate(c.lastVisitDate());
        r.setDueDate(today);
        r.setMethod(method);
        r.setTimeline(buildCreateTimeline(rule.getRuleNo()));
        r.setRuleNo(rule.getRuleNo());
        r.setStoreCode(storeCode);
        r.setCreatedBy("SYSTEM");
        recallRepo.save(r);
        return new Outcome("SUCCESS", r.getRecallNo(), "生成复诊召回");
    }

    /** timeline 首条：[{at,by,action,detail}]（Jackson 构造防转义问题；action 中文与 RecallService 一致）。 */
    private String buildCreateTimeline(String ruleNo) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("at", OffsetDateTime.now(BIZ_TZ).toString());
        entry.put("by", "SYSTEM");
        entry.put("action", "创建复诊提醒");
        entry.put("detail", "Flow 引擎自动创建（规则 " + ruleNo + "）");
        ArrayNode arr = MAPPER.createArrayNode();
        arr.add(entry);
        try {
            return MAPPER.writeValueAsString(arr);
        } catch (Exception ex) {
            log.warn("timeline 序列化失败（落空数组）：{}", ex.getMessage());
            return "[]";
        }
    }

    /** 落执行日志（idem 键写入即占位：当日不再重试该候选，次日 triggerDate 变化自然重试）。 */
    private void saveLog(AutomationRule rule, FlowCandidate c, LocalDate today, Outcome o, String idemKey) {
        AutomationLog l = new AutomationLog();
        l.setRuleNo(rule.getRuleNo());
        l.setCustomerId(c.customerId());
        l.setTriggerDate(today);
        l.setActionType(rule.getActionType());
        l.setActionRef(o.actionRef());
        l.setStatus(o.status());
        l.setMessage(o.message());
        l.setIdemKey(idemKey);
        logRepo.save(l);
    }

    /** 规则门店码空白=全部门店；否则候选门店码须精确相等（候选无门店码则限定规则不命中）。 */
    private boolean storeMatches(String ruleStoreCode, String candidateStoreCode) {
        if (ruleStoreCode == null || ruleStoreCode.isBlank()) return true;
        if (candidateStoreCode == null || candidateStoreCode.isBlank()) return false;
        return ruleStoreCode.equals(candidateStoreCode);
    }

    private static JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            log.warn("Flow 配置 JSON 解析失败（按缺省处理）：{}", ex.getMessage());
            return null;
        }
    }

    private static String textOr(JsonNode node, String field, String dft) {
        if (node == null) return dft;
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? dft : v.asText();
    }
}
