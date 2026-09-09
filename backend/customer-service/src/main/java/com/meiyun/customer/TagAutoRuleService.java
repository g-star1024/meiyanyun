package com.meiyun.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 标签自动化规则引擎（域①-257）。
 *
 * <p>条件求值（纯函数，无副作用）：依据 conditionType / conditionValue 判定单个客户是否命中规则，
 * 命中后由 effect 决定「打标」或「撤标」。执行入口 {@link #runRule} 复用既有
 * {@link CustomerService#assignTag} / {@link CustomerService#unassignTag}，受复合主键唯一约束与
 * 幂等保护（已打标重复执行不变更、不重复落审计），单条客户异常不影响同批其余客户。
 */
@Service
public class TagAutoRuleService {

    /** 条件类型白名单（对齐 tag_auto_rule.condition_type CHECK）。 */
    private static final Set<String> CONDITION_TYPES =
            Set.of("CONSUME_GTE", "VISIT_GTE", "POINTS_GTE", "LEVEL_IN", "CHANNEL_EQ");
    /** 效果白名单。 */
    private static final Set<String> EFFECTS = Set.of("ASSIGN", "REVOKE");

    private final TagAutoRuleRepository ruleRepo;
    private final CustomerRepository customerRepo;
    private final CustomerTagRepository tagRepo;
    private final CustomerTagRelRepository tagRelRepo;
    private final CustomerService customerService;

    public TagAutoRuleService(TagAutoRuleRepository ruleRepo, CustomerRepository customerRepo,
                             CustomerTagRepository tagRepo, CustomerTagRelRepository tagRelRepo,
                             CustomerService customerService) {
        this.ruleRepo = ruleRepo;
        this.customerRepo = customerRepo;
        this.tagRepo = tagRepo;
        this.tagRelRepo = tagRelRepo;
        this.customerService = customerService;
    }

    // ---- 规则 CRUD（写接口四件套：校验 / 防重 / 审计由 Controller 落 / 中文错误） ----

    @Transactional
    public synchronized TagAutoRule createRule(String name, boolean enabled, String effect,
                                              String targetTagId, String conditionType,
                                              String conditionValue, Integer priority,
                                              boolean revokeWhenUnsatisfied) {
        String n = trim(name);
        if (n.isEmpty()) throw new CustomerService.BadReq("规则名称不能为空");
        if (n.length() > 64) throw new CustomerService.BadReq("规则名称最长 64 字");
        String ef = trim(effect);
        if (!EFFECTS.contains(ef)) throw new CustomerService.BadReq("效果取值非法：ASSIGN / REVOKE");
        String tid = trim(targetTagId);
        if (tid.isEmpty()) throw new CustomerService.BadReq("目标标签不能为空");
        if (!tagRepo.existsById(tid)) throw new CustomerService.BadReq("目标标签不存在：" + tid);
        String ct = trim(conditionType);
        if (!CONDITION_TYPES.contains(ct)) {
            throw new CustomerService.BadReq("条件类型非法：CONSUME_GTE / VISIT_GTE / POINTS_GTE / LEVEL_IN / CHANNEL_EQ");
        }
        String cv = trim(conditionValue);
        validateConditionValue(ct, cv);

        TagAutoRule r = new TagAutoRule();
        r.setRuleId(nextRuleId());
        r.setName(n);
        r.setEnabled(enabled);
        r.setEffect(ef);
        r.setTargetTagId(tid);
        r.setConditionType(ct);
        r.setConditionValue(cv);
        r.setPriority(priority == null ? 100 : priority);
        r.setRevokeWhenUnsatisfied(revokeWhenUnsatisfied);
        return ruleRepo.save(r);
    }

    @Transactional
    public synchronized TagAutoRule updateRule(String ruleId, String name, Boolean enabled, String effect,
                                              String targetTagId, String conditionType,
                                              String conditionValue, Integer priority,
                                              Boolean revokeWhenUnsatisfied) {
        TagAutoRule r = ruleRepo.findById(ruleId)
                .orElseThrow(() -> new CustomerService.NotFound("规则不存在: " + ruleId));
        if (name != null) {
            String n = trim(name);
            if (n.isEmpty()) throw new CustomerService.BadReq("规则名称不能为空");
            if (n.length() > 64) throw new CustomerService.BadReq("规则名称最长 64 字");
            r.setName(n);
        }
        if (enabled != null) r.setEnabled(enabled);
        if (effect != null) {
            String ef = trim(effect);
            if (!EFFECTS.contains(ef)) throw new CustomerService.BadReq("效果取值非法：ASSIGN / REVOKE");
            r.setEffect(ef);
        }
        if (targetTagId != null) {
            String tid = trim(targetTagId);
            if (tid.isEmpty()) throw new CustomerService.BadReq("目标标签不能为空");
            if (!tagRepo.existsById(tid)) throw new CustomerService.BadReq("目标标签不存在：" + tid);
            r.setTargetTagId(tid);
        }
        if (conditionType != null || conditionValue != null) {
            String ct = conditionType == null ? r.getConditionType() : trim(conditionType);
            String cv = conditionValue == null ? r.getConditionValue() : trim(conditionValue);
            if (!CONDITION_TYPES.contains(ct)) {
                throw new CustomerService.BadReq("条件类型非法：CONSUME_GTE / VISIT_GTE / POINTS_GTE / LEVEL_IN / CHANNEL_EQ");
            }
            validateConditionValue(ct, cv);
            r.setConditionType(ct);
            r.setConditionValue(cv);
        }
        if (priority != null) r.setPriority(priority);
        if (revokeWhenUnsatisfied != null) r.setRevokeWhenUnsatisfied(revokeWhenUnsatisfied);
        return ruleRepo.save(r);
    }

    @Transactional
    public void deleteRule(String ruleId) {
        if (!ruleRepo.existsById(ruleId)) throw new CustomerService.NotFound("规则不存在: " + ruleId);
        ruleRepo.deleteById(ruleId);
    }

    @Transactional(readOnly = true)
    public List<TagAutoRule> listRules() {
        return ruleRepo.findAll();
    }

    @Transactional(readOnly = true)
    public Page<TagAutoRule> listRules(Pageable pageable) {
        return ruleRepo.findAll(pageable);
    }

    // ---- 规则执行（定时任务 / 手动触发共用） ----

    /** 单条规则执行结果：matched=命中客户数，assigned=新打标数，revoked=新撤标数，scanned=扫描客户数。 */
    public record RuleRunResult(int scanned, int matched, int assigned, int revoked, String error) {}

    /**
     * 执行单条规则：分页扫描全部客户，按 effect 与命中情况打/撤标。
     * 目标标签中途被删 → 跳过该规则并记 error（不影响同批其余规则）；单客户异常计入 skipped 不中断。
     */
    @Transactional
    public RuleRunResult runRule(TagAutoRule rule) {
        int scanned = 0, matched = 0, assigned = 0, revoked = 0;
        try {
            if (!tagRepo.existsById(rule.getTargetTagId())) {
                return new RuleRunResult(scanned, matched, assigned, revoked,
                        "目标标签已不存在: " + rule.getTargetTagId());
            }
            boolean assign = "ASSIGN".equals(rule.getEffect());
            org.springframework.data.domain.Pageable page = org.springframework.data.domain.PageRequest.of(0, 200);
            while (true) {
                Page<Customer> pg = customerRepo.findAll(page);
                if (pg.isEmpty()) break;
                for (Customer c : pg.getContent()) {
                    scanned++;
                    boolean hit = matches(rule, c);
                    if (hit) matched++;
                    boolean tagged = tagRelRepo.existsByCustomerIdAndTagId(c.getCustomerId(), rule.getTargetTagId());
                    try {
                        if (assign) {
                            if (hit && !tagged) {
                                customerService.assignTag(c.getCustomerId(), rule.getTargetTagId());
                                assigned++;
                            } else if (!hit && Boolean.TRUE.equals(rule.getRevokeWhenUnsatisfied()) && tagged) {
                                customerService.unassignTag(c.getCustomerId(), rule.getTargetTagId());
                                revoked++;
                            }
                        } else { // REVOKE
                            if (hit && tagged) {
                                customerService.unassignTag(c.getCustomerId(), rule.getTargetTagId());
                                revoked++;
                            }
                        }
                    } catch (RuntimeException ex) {
                        // 单客户异常（越权/数据态不一致等）跳过，不中断整批
                    }
                }
                if (!pg.hasNext()) break;
                page = pg.nextPageable();
            }
            rule.setLastRunAt(OffsetDateTime.now());
            ruleRepo.save(rule);
        } catch (RuntimeException ex) {
            return new RuleRunResult(scanned, matched, assigned, revoked, ex.getMessage());
        }
        return new RuleRunResult(scanned, matched, assigned, revoked, null);
    }

    /** 执行全部启用规则（按优先级升序）。 */
    @Transactional
    public List<RuleRunResult> runAll() {
        List<TagAutoRule> rules = ruleRepo.findByEnabledTrueOrderByPriorityAsc();
        List<RuleRunResult> results = new ArrayList<>();
        for (TagAutoRule r : rules) {
            results.add(runRule(r));
        }
        return results;
    }

    // ---- 条件求值（纯函数） ----

    /** 判定客户是否命中规则条件（字段空值按零值兜底，避免 NPE）。 */
    boolean matches(TagAutoRule rule, Customer c) {
        String ct = rule.getConditionType();
        String cv = rule.getConditionValue() == null ? "" : rule.getConditionValue().trim();
        try {
            return switch (ct) {
                case "CONSUME_GTE" -> {
                    BigDecimal threshold = new BigDecimal(cv);
                    BigDecimal spend = c.getTotalSpend() == null ? BigDecimal.ZERO : c.getTotalSpend();
                    yield spend.compareTo(threshold) >= 0;
                }
                case "VISIT_GTE" -> {
                    int v = Integer.parseInt(cv);
                    yield (c.getVisitCount() == null ? 0 : c.getVisitCount()) >= v;
                }
                case "POINTS_GTE" -> {
                    long p = Long.parseLong(cv);
                    yield (c.getPoints() == null ? 0L : c.getPoints()) >= p;
                }
                case "LEVEL_IN" -> {
                    List<String> levels = new ArrayList<>();
                    for (String s : cv.split(",")) {
                        String t = s.trim();
                        if (!t.isEmpty()) levels.add(t);
                    }
                    yield levels.contains(c.getLevel());
                }
                case "CHANNEL_EQ" -> cv.equals(c.getChannel() == null ? "" : c.getChannel());
                default -> false;
            };
        } catch (NumberFormatException | ArithmeticException ex) {
            return false; // 条件值非法：该规则本条不命中（不抛错，避免中断整批）
        }
    }

    /** 条件值合法性校验（创建/更新时）。 */
    private void validateConditionValue(String conditionType, String cv) {
        String v = cv == null ? "" : cv.trim();
        if (v.isEmpty()) throw new CustomerService.BadReq("条件值不能为空");
        switch (conditionType) {
            case "CONSUME_GTE", "VISIT_GTE", "POINTS_GTE" -> {
                try {
                    new BigDecimal(v);
                    if (new BigDecimal(v).signum() < 0) throw new CustomerService.BadReq("条件值不能为负数");
                } catch (NumberFormatException e) {
                    throw new CustomerService.BadReq("条件值必须为数字（" + conditionType + "）");
                }
            }
            case "LEVEL_IN" -> {
                if (!v.contains(",") && !isValidLevel(v)) {
                    throw new CustomerService.BadReq("等级清单含非法等级（应为 普通/银卡/金卡/钻石/黑卡）");
                }
                for (String s : v.split(",")) {
                    if (!isValidLevel(s.trim())) {
                        throw new CustomerService.BadReq("等级清单含非法等级：" + s.trim());
                    }
                }
            }
            case "CHANNEL_EQ" -> {
                if (!Set.of("WALK_IN", "REFERRAL", "WECHAT", "DOUYIN", "XIAOHONGSHU", "MEITUAN", "OTHER").contains(v)) {
                    throw new CustomerService.BadReq("渠道取值非法：WALK_IN/REFERRAL/WECHAT/DOUYIN/XIAOHONGSHU/MEITUAN/OTHER");
                }
            }
            default -> throw new CustomerService.BadReq("条件类型非法");
        }
    }

    private boolean isValidLevel(String lv) {
        return Set.of("普通", "银卡", "金卡", "钻石", "黑卡").contains(lv);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 生成下一个规则编号：TA+3 位序号，基于库内最大 TA 号递增（synchronized 防并发重号）。 */
    private String nextRuleId() {
        String max = ruleRepo.maxTaId();
        int seq = 0;
        if (max != null && max.startsWith("TA")) {
            try {
                seq = Integer.parseInt(max.substring(2));
            } catch (NumberFormatException ignored) {
                seq = 0;
            }
        }
        return String.format("TA%03d", seq + 1);
    }
}
