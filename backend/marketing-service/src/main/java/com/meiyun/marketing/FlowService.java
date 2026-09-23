package com.meiyun.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * Flow 规则管理（P5-B90，v1 仅 REST 无前端页，DESIGN D1）。
 *
 * <p>规则 CRUD＋启停 toggle＋执行日志查询，写动作全量审计（audit_log，bizType=FLOW_RULE）。
 * trigger/condition/action 配置以 JSON 文本承载（与实体列一致），保存前强制合法 JSON 校验＋
 * 枚举白名单校验（trigger_type 三型 / action_type 两型 / 动作配置枚举键值），防误配污染引擎
 * （DESIGN §7「规则误配产生大量任务」对策：播种保守＋toggle 可停＋日志可追溯）。
 */
@Service
public class FlowService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> TRIGGER_TYPES = Set.of("BIRTHDAY", "DORMANT_DAYS", "VISIT_GAP_DAYS");
    private static final Set<String> ACTION_TYPES = Set.of("CREATE_CARE_TASK", "CREATE_RECALL");
    private static final Set<String> CARE_TYPES = Set.of("BIRTHDAY", "HOLIDAY", "REPURCHASE", "REACTIVATE");
    private static final Set<String> CARE_CHANNELS = Set.of("SMS", "WECHAT", "PHONE");
    private static final Set<String> RECALL_METHODS = Set.of("PHONE", "WECHAT", "SMS", "IN_STORE");

    private final AutomationRuleRepository ruleRepo;
    private final AutomationLogRepository logRepo;
    private final BizNoGenerator bizNo;
    private final AuditRecorder audit;

    public FlowService(AutomationRuleRepository ruleRepo, AutomationLogRepository logRepo,
                       BizNoGenerator bizNo, AuditRecorder audit) {
        this.ruleRepo = ruleRepo;
        this.logRepo = logRepo;
        this.bizNo = bizNo;
        this.audit = audit;
    }

    /** 规则视图（配置列直出 JSON 文本，与实体一致）。 */
    public record RuleView(Long id, String ruleNo, String name, String triggerType, String triggerConfig,
                           String conditionConfig, String actionType, String actionConfig, Boolean enabled,
                           String storeCode, String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    /** 规则命令（配置列为 JSON 文本；enabled 仅创建时生效，后续走 toggle）。 */
    public record RuleCmd(String name, String triggerType, String triggerConfig, String conditionConfig,
                          String actionType, String actionConfig, String storeCode, Boolean enabled) {}

    /** 执行日志视图。 */
    public record LogView(Long id, String ruleNo, String customerId, LocalDate triggerDate, String actionType,
                          String actionRef, String status, String message, String idemKey,
                          OffsetDateTime createdAt) {}

    /** 规则列表（enabled 过滤可空=全部）。 */
    public List<RuleView> list(Boolean enabled) {
        List<AutomationRule> rules = Boolean.TRUE.equals(enabled)
                ? ruleRepo.findByEnabledTrueOrderByCreatedAtDesc()
                : ruleRepo.findAllByOrderByCreatedAtDesc();
        return rules.stream().filter(r -> enabled == null || enabled.equals(r.getEnabled()))
                .map(FlowService::toView).toList();
    }

    @Transactional
    public RuleView create(RuleCmd cmd) {
        validate(cmd, true);
        AutomationRule r = new AutomationRule();
        r.setRuleNo(bizNo.next("AR", like -> ruleRepo.findTopByRuleNoLikeOrderByRuleNoDesc(like)
                .map(AutomationRule::getRuleNo).orElse(null)));
        apply(r, cmd);
        r.setEnabled(cmd.enabled() == null || cmd.enabled());
        r.setCreatedBy(DataScope.currentActor());
        AutomationRule saved = ruleRepo.save(r);
        audit(saved.getRuleNo(), "CREATE",
                "{\"name\":\"" + escape(saved.getName()) + "\",\"triggerType\":\"" + saved.getTriggerType()
                        + "\",\"actionType\":\"" + saved.getActionType() + "\"}");
        return toView(saved);
    }

    @Transactional
    public RuleView update(Long id, RuleCmd cmd) {
        validate(cmd, false);
        AutomationRule r = ruleRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在：" + id));
        apply(r, cmd);
        r.setUpdatedAt(OffsetDateTime.now());
        AutomationRule saved = ruleRepo.save(r);
        audit(saved.getRuleNo(), "UPDATE",
                "{\"name\":\"" + escape(saved.getName()) + "\",\"triggerType\":\"" + saved.getTriggerType()
                        + "\",\"actionType\":\"" + saved.getActionType() + "\"}");
        return toView(saved);
    }

    /** 启停翻转（DESIGN §7：规则误配可随时停用止血）。 */
    @Transactional
    public RuleView toggle(Long id) {
        AutomationRule r = ruleRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在：" + id));
        r.setEnabled(!Boolean.TRUE.equals(r.getEnabled()));
        r.setUpdatedAt(OffsetDateTime.now());
        AutomationRule saved = ruleRepo.save(r);
        audit(saved.getRuleNo(), "TOGGLE", "{\"enabled\":" + saved.getEnabled() + "}");
        return toView(saved);
    }

    /** 执行日志查询（ruleNo/date 可空四组合；无过滤限 200 条防爆量）。 */
    public List<LogView> logs(String ruleNo, String date) {
        LocalDate d = null;
        if (date != null && !date.isBlank()) {
            try {
                d = LocalDate.parse(date.trim());
            } catch (DateTimeParseException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date 格式须为 yyyy-MM-dd：" + date);
            }
        }
        boolean hasRule = ruleNo != null && !ruleNo.isBlank();
        List<AutomationLog> rows;
        if (hasRule && d != null) {
            rows = logRepo.findByRuleNoAndTriggerDateOrderByIdDesc(ruleNo.trim(), d);
        } else if (hasRule) {
            rows = logRepo.findByRuleNoOrderByIdDesc(ruleNo.trim());
        } else if (d != null) {
            rows = logRepo.findByTriggerDateOrderByIdDesc(d);
        } else {
            rows = logRepo.findTop200ByOrderByIdDesc();
        }
        return rows.stream().map(FlowService::toLogView).toList();
    }

    // ==================== 内部 ====================

    private void validate(RuleCmd cmd, boolean creating) {
        if (cmd == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        if (cmd.name() == null || cmd.name().isBlank() || cmd.name().length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则名必填且 ≤64 字");
        }
        if (!TRIGGER_TYPES.contains(cmd.triggerType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "trigger_type 不合法：仅支持 BIRTHDAY/DORMANT_DAYS/VISIT_GAP_DAYS");
        }
        if (!ACTION_TYPES.contains(cmd.actionType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "action_type 不合法：仅支持 CREATE_CARE_TASK/CREATE_RECALL");
        }
        JsonNode trig = parseJson(cmd.triggerConfig(), "trigger_config");
        if (creating && trig == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trigger_config 必填（JSON）");
        }
        parseJson(cmd.conditionConfig(), "condition_config");
        JsonNode action = parseJson(cmd.actionConfig(), "action_config");
        // 动作配置枚举键值存在即校验白名单（引擎兜底默认值之外的显式误配在保存期拦截）
        if (action != null) {
            if ("CREATE_CARE_TASK".equals(cmd.actionType())) {
                checkEnum(action, "careType", CARE_TYPES);
                checkEnum(action, "channel", CARE_CHANNELS);
            } else {
                checkEnum(action, "method", RECALL_METHODS);
            }
        }
        if (cmd.storeCode() != null && cmd.storeCode().length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "store_code 超长（>32）");
        }
    }

    private static void apply(AutomationRule r, RuleCmd cmd) {
        r.setName(cmd.name().trim());
        r.setTriggerType(cmd.triggerType());
        r.setTriggerConfig(cmd.triggerConfig());
        r.setConditionConfig(cmd.conditionConfig());
        r.setActionType(cmd.actionType());
        r.setActionConfig(cmd.actionConfig());
        r.setStoreCode(cmd.storeCode() == null || cmd.storeCode().isBlank() ? null : cmd.storeCode().trim());
    }

    private static JsonNode parseJson(String json, String field) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 非法 JSON：" + ex.getMessage());
        }
    }

    private static void checkEnum(JsonNode cfg, String field, Set<String> whitelist) {
        JsonNode v = cfg.get(field);
        if (v != null && !v.isNull() && !whitelist.contains(v.asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "action_config." + field + " 不合法：" + v.asText() + "（仅支持 " + String.join("/", whitelist) + "）");
        }
    }

    private void audit(String txnNo, String action, String payload) {
        audit.record("FLOW_RULE", txnNo, DataScope.currentActor(), action, payload);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static RuleView toView(AutomationRule r) {
        return new RuleView(r.getId(), r.getRuleNo(), r.getName(), r.getTriggerType(), r.getTriggerConfig(),
                r.getConditionConfig(), r.getActionType(), r.getActionConfig(), r.getEnabled(), r.getStoreCode(),
                r.getCreatedBy(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private static LogView toLogView(AutomationLog l) {
        return new LogView(l.getId(), l.getRuleNo(), l.getCustomerId(), l.getTriggerDate(), l.getActionType(),
                l.getActionRef(), l.getStatus(), l.getMessage(), l.getIdemKey(), l.getCreatedAt());
    }
}
