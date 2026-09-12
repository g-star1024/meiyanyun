package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 赠金高级规则与发放（域⑤ 赠金）。
 *
 * <p>写接口四件套：① 参数校验（类型白名单、额度&gt;0、有效期&gt;0、适用门店逗号校验）；
 * ② 幂等（customer_grant.idem_key 非空唯一，手工/规则统一入口，唯一冲突后重查返回既有记录，changed=false）；
 * ③ 全动作审计（bizType=GRANT_RULE / GRANT_ISSUE，payload 为全字段 JSON）；
 * ④ 中文错误。
 *
 * <p>客户存在性 + 归属门店由客户域 internal 投影硬校验（客户不存在 400、客户域不可用 502，
 * 真实资金权益不允许凭空发放或降级照发）。
 *
 * <p>范围边界：本期覆盖「规则配置 + 赠金发放 + 账本余额 + 收银台抵扣 + 过期 + 报表」。
 * 抵扣按到期时间 FIFO 跨券扣减并落 grant_deduction 流水（详见 {@link #deduct}）；
 * 退款回加（REFUND 反向流水）需交易域退款链路配合，列为下游 Backlog。
 */
@Service
public class GrantService {

    /** 业务时区：容器 JVM 默认 UTC，所有「现在/过期」显式按 Asia/Shanghai 计算。 */
    public static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");
    /** 规则号前缀（BizNoGenerator 生成 GRT+yyyyMMdd-+6 位序号）。 */
    public static final String RULE_NO_PREFIX = "GRT";

    /** 合法赠金类型。 */
    public static final List<String> GRANT_TYPES = List.of("CONSUME_THRESHOLD", "ACTIVITY", "NEWCOMER");

    private final GrantRuleRepository ruleRepo;
    private final CustomerGrantRepository grantRepo;
    private final GrantDeductionRepository deductionRepo;
    private final AuditRecorder audit;
    private final BizNoGenerator bizNoGenerator;
    private final CustomerDirectoryClient customerDirectory;

    public GrantService(GrantRuleRepository ruleRepo, CustomerGrantRepository grantRepo,
                        GrantDeductionRepository deductionRepo, AuditRecorder audit,
                        BizNoGenerator bizNoGenerator, CustomerDirectoryClient customerDirectory) {
        this.ruleRepo = ruleRepo;
        this.grantRepo = grantRepo;
        this.deductionRepo = deductionRepo;
        this.audit = audit;
        this.bizNoGenerator = bizNoGenerator;
        this.customerDirectory = customerDirectory;
    }

    // ==================== 规则 ====================

    public List<GrantRule> listRules() {
        return ruleRepo.findAll();
    }

    @Transactional
    public GrantRule createRule(CreateRuleCmd cmd) {
        validateRule(cmd.name(), cmd.grantType(), cmd.grantAmountFen(), cmd.expireMonths(), cmd.thresholdFen());
        GrantRule r = new GrantRule();
        r.setRuleId(bizNoGenerator.next(RULE_NO_PREFIX, like ->
                Optional.ofNullable(ruleRepo.findTopByRuleIdLikeOrderByRuleIdDesc(like))
                        .map(GrantRule::getRuleId).orElse(null)));
        r.setName(cmd.name().trim());
        r.setGrantType(cmd.grantType());
        r.setThresholdFen(cmd.thresholdFen());
        r.setGrantAmountFen(cmd.grantAmountFen());
        r.setExpireMonths(cmd.expireMonths());
        r.setApplicableStores(cmd.applicableStores() == null ? "" : cmd.applicableStores().trim());
        r.setStatus("ENABLED");
        r.setPriority(cmd.priority() == null ? 0 : cmd.priority());
        ruleRepo.save(r);
        audit("GRANT_RULE", "CREATE", r.getRuleId(), rulePayload(r));
        return r;
    }

    @Transactional
    public boolean updateRule(String id, CreateRuleCmd cmd) {
        GrantRule r = ruleRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "赠金规则不存在：" + id));
        validateRule(cmd.name(), cmd.grantType(), cmd.grantAmountFen(), cmd.expireMonths(), cmd.thresholdFen());
        int newPriority = cmd.priority() == null ? r.getPriority() : cmd.priority();
        boolean changed = !eq(r.getName(), cmd.name().trim())
                || !eq(r.getGrantType(), cmd.grantType())
                || !eq(r.getThresholdFen(), cmd.thresholdFen())
                || !eq(r.getGrantAmountFen(), cmd.grantAmountFen())
                || r.getExpireMonths() != cmd.expireMonths()
                || r.getPriority() != newPriority
                || !eq(r.getApplicableStores(), cmd.applicableStores() == null ? "" : cmd.applicableStores().trim());
        if (!changed) return false;
        r.setName(cmd.name().trim());
        r.setGrantType(cmd.grantType());
        r.setThresholdFen(cmd.thresholdFen());
        r.setGrantAmountFen(cmd.grantAmountFen());
        r.setExpireMonths(cmd.expireMonths());
        r.setApplicableStores(cmd.applicableStores() == null ? "" : cmd.applicableStores().trim());
        r.setPriority(newPriority);
        ruleRepo.save(r);
        audit("GRANT_RULE", "UPDATE", id, rulePayload(r));
        return true;
    }

    @Transactional
    public boolean toggleRule(String id, boolean enabled) {
        GrantRule r = ruleRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "赠金规则不存在：" + id));
        String target = enabled ? "ENABLED" : "DISABLED";
        if (target.equals(r.getStatus())) return false;
        r.setStatus(target);
        ruleRepo.save(r);
        audit("GRANT_RULE", "TOGGLE", id, Map.of("status", target));
        return true;
    }

    // ==================== 发放 ====================

    /**
     * 手动发放赠金（运营补偿/活动定向）。
     * 幂等：客户端可在 IssueCmd.idemKey 传业务幂等号（如 MANUAL:{customerId}:{审批单号}）；
     * 不传则服务端生成 UUID（仅防同请求重试无锚点，运营侧应优先传业务号）。
     * ruleId 留空（手工发放不绑定规则），幂等由非空 idem_key 唯一约束兜底。
     */
    @Transactional
    public CustomerGrant issueGrant(IssueCmd cmd) {
        if (cmd.customerId() == null || cmd.customerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户ID不可为空");
        }
        if (cmd.amountFen() == null || cmd.amountFen() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "赠金额度须为正数（单位：分）");
        }
        if (cmd.expireMonths() == null || cmd.expireMonths() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "赠金有效期须为正数（月）");
        }
        String customerId = cmd.customerId().trim();
        CustomerDirectoryClient.CustomerDirectory dir = customerDirectory.requireCustomer(customerId);
        String idemKey = normalizeIdemKey(cmd.idemKey(),
                "MANUAL:" + customerId + ":" + UUID.randomUUID());

        Optional<CustomerGrant> replay = grantRepo.findByIdemKey(idemKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        CustomerGrant g = new CustomerGrant();
        g.setCustomerId(customerId);
        g.setRuleId(null);
        g.setAmountFen(cmd.amountFen());
        g.setBalanceFen(cmd.amountFen());
        g.setExpireAt(OffsetDateTime.now(BIZ_ZONE).plusMonths(cmd.expireMonths()));
        g.setStatus("VALID");
        g.setSourceBizRef(idemKey);
        g.setIdemKey(idemKey);
        g.setStoreCode(dir.storeCode() == null ? "" : dir.storeCode());
        saveIdempotent(g);
        audit("GRANT_ISSUE", "MANUAL", idemKey,
                Map.of("customerId", customerId, "storeCode", g.getStoreCode(),
                        "amountFen", cmd.amountFen(), "expireMonths", cmd.expireMonths()));
        return g;
    }

    /**
     * 规则触发发放（供交易/活动链路调用）。幂等：idem_key=RULE:{ruleId}:{sourceBizRef}，
     * 先查后插 + 唯一约束兜底（并发双发只落一行，冲突方重查返回既有记录）。
     */
    @Transactional
    public CustomerGrant issueByRule(String customerId, GrantRule rule, String sourceBizRef) {
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户ID不可为空");
        }
        if (rule == null || rule.getRuleId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "赠金规则不可为空");
        }
        if (sourceBizRef == null || sourceBizRef.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则发放业务来源号不可为空");
        }
        String cid = customerId.trim();
        String idemKey = "RULE:" + rule.getRuleId() + ":" + sourceBizRef.trim();
        Optional<CustomerGrant> existing = grantRepo.findByIdemKey(idemKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        CustomerDirectoryClient.CustomerDirectory dir = customerDirectory.requireCustomer(cid);

        CustomerGrant g = new CustomerGrant();
        g.setCustomerId(cid);
        g.setRuleId(rule.getRuleId());
        g.setAmountFen(rule.getGrantAmountFen());
        g.setBalanceFen(rule.getGrantAmountFen());
        g.setExpireAt(OffsetDateTime.now(BIZ_ZONE).plusMonths(rule.getExpireMonths()));
        g.setStatus("VALID");
        g.setSourceBizRef(sourceBizRef.trim());
        g.setIdemKey(idemKey);
        g.setStoreCode(dir.storeCode() == null ? "" : dir.storeCode());
        CustomerGrant saved = saveIdempotent(g);
        if (saved != g) {
            return saved;
        }
        audit("GRANT_ISSUE", "RULE", idemKey,
                Map.of("customerId", cid, "storeCode", g.getStoreCode(), "ruleId", rule.getRuleId(),
                        "sourceBizRef", sourceBizRef.trim(), "amountFen", rule.getGrantAmountFen()));
        return g;
    }

    /**
     * insert 冲突（并发同 idem_key）不抛 500，重查返回既有记录（幂等语义）。
     */
    private CustomerGrant saveIdempotent(CustomerGrant g) {
        try {
            return grantRepo.save(g);
        } catch (DataIntegrityViolationException dup) {
            return grantRepo.findByIdemKey(g.getIdemKey())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "赠金发放唯一键冲突且无法重查，请稍后重试"));
        }
    }

    // ==================== 抵扣（收银台内部调用） ====================

    /**
     * 收银台赠金抵扣（域⑤ ←→ 交易域）。按「先到期先用」FIFO 跨券扣减，单券可部分扣、扣尽置 USED。
     *
     * <p>幂等：biz_ref = 订单号，同单重放原样返回既有流水不双扣（与储值扣款「同单单笔」口径一致）。
     * 并发：findUsableForUpdate 行锁串行化，防同客户并发下单把同一张券扣两次。
     * 余额不足直接 422 拒绝（不做「有多少扣多少」），由收银台先查余额再决定抵扣额，
     * 避免调用方以为全额抵扣成功而少收钱。
     *
     * @return 本次实际写入（或重放命中）的抵扣流水，可能多行
     */
    @Transactional
    public List<GrantDeduction> deduct(String customerId, Long amountFen, String bizRef,
                                       String storeCode, String operator) {
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户ID不可为空");
        }
        if (amountFen == null || amountFen <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "抵扣金额须为正数（单位：分）");
        }
        if (bizRef == null || bizRef.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "抵扣业务单号不可为空");
        }
        String cid = customerId.trim();
        String ref = bizRef.trim();

        List<GrantDeduction> replay = deductionRepo.findByBizRefOrderByIdAsc(ref);
        if (!replay.isEmpty()) {
            return replay;
        }

        OffsetDateTime now = OffsetDateTime.now(BIZ_ZONE);
        List<CustomerGrant> usable = grantRepo.findUsableForUpdate(cid, now);
        long available = usable.stream().mapToLong(CustomerGrant::getBalanceFen).sum();
        if (available < amountFen) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "赠金余额不足：可用 " + yuan(available) + " 元，本次需抵扣 " + yuan(amountFen) + " 元");
        }

        List<GrantDeduction> written = new java.util.ArrayList<>();
        long remain = amountFen;
        for (CustomerGrant g : usable) {
            if (remain <= 0) break;
            long take = Math.min(remain, g.getBalanceFen());
            long after = g.getBalanceFen() - take;
            g.setBalanceFen(after);
            if (after == 0) {
                g.setStatus("USED");
            }
            grantRepo.save(g);

            GrantDeduction d = new GrantDeduction();
            d.setBizRef(ref);
            d.setGrantId(g.getId());
            d.setCustomerId(cid);
            d.setAmountFen(take);
            d.setBalanceAfterFen(after);
            d.setChangeType("DEDUCT");
            d.setStoreCode(storeCode == null ? "" : storeCode.trim());
            d.setOperator(operator == null ? "" : operator.trim());
            written.add(d);
            remain -= take;
        }

        List<GrantDeduction> saved;
        try {
            saved = deductionRepo.saveAll(written);
        } catch (DataIntegrityViolationException dup) {
            List<GrantDeduction> exist = deductionRepo.findByBizRefOrderByIdAsc(ref);
            if (!exist.isEmpty()) {
                return exist;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "赠金抵扣唯一键冲突且无法重查，请稍后重试");
        }

        audit("GRANT_DEDUCT", "ORDER", ref,
                Map.of("customerId", cid, "storeCode", storeCode == null ? "" : storeCode,
                        "operator", operator == null ? "" : operator,
                        "amountFen", amountFen, "grantCount", saved.size(),
                        "balanceAfterFen", balance(cid)));
        return saved;
    }

    /** 分转元的中文错误文案用格式（仅用于提示，不参与计算）。 */
    private static String yuan(long fen) {
        return java.math.BigDecimal.valueOf(fen, 2).toPlainString();
    }

    // ==================== 查询 / 报表 ====================

    public Long balance(String customerId) {
        Long b = grantRepo.sumBalanceByCustomer(customerId);
        return b == null ? 0L : b;
    }

    public Map<String, Object> report() {
        List<Object[]> rows = grantRepo.sumBalanceGroupByCustomer();
        List<Map<String, Object>> byCustomer = new java.util.ArrayList<>();
        long totalBalance = 0;
        for (Object[] row : rows) {
            String cid = (String) row[0];
            Long bal = ((Number) row[1]).longValue();
            totalBalance += bal;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("customerId", cid);
            m.put("balanceFen", bal);
            byCustomer.add(m);
        }
        long validCount = grantRepo.countByStatus("VALID");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("perCustomer", byCustomer);
        out.put("totalBalanceFen", totalBalance);
        out.put("validGrantCount", validCount);
        return out;
    }

    // ==================== 过期（Job 逐条独立事务调用） ====================

    /**
     * 过期单条赠金（单条一事务，由无事务 Job 限批遍历调用，单条失败不毒化整批）。
     * 已是终态（非 VALID）返回 false，不重复清零/审计。
     */
    @Transactional
    public boolean expireOne(Long id, OffsetDateTime now) {
        CustomerGrant g = grantRepo.findById(id).orElse(null);
        if (g == null || !"VALID".equals(g.getStatus()) || g.getExpireAt().isAfter(now)) {
            return false;
        }
        g.setStatus("EXPIRED");
        g.setBalanceFen(0L);
        grantRepo.save(g);
        audit("GRANT_ISSUE", "EXPIRE", g.getIdemKey(),
                Map.of("customerId", g.getCustomerId(), "grantId", g.getId()));
        return true;
    }

    // ==================== 内部 ====================

    private String normalizeIdemKey(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String key = raw.trim();
        if (key.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "幂等号长度不可超过 120");
        }
        return key;
    }

    private Map<String, Object> rulePayload(GrantRule r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ruleId", r.getRuleId());
        p.put("name", r.getName());
        p.put("grantType", r.getGrantType());
        p.put("thresholdFen", r.getThresholdFen());
        p.put("grantAmountFen", r.getGrantAmountFen());
        p.put("expireMonths", r.getExpireMonths());
        p.put("applicableStores", r.getApplicableStores());
        p.put("priority", r.getPriority());
        p.put("status", r.getStatus());
        return p;
    }

    private void validateRule(String name, String type, Long amount, Integer months, Long threshold) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则名称不可为空");
        }
        if (!GRANT_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "赠金类型不合法：仅支持 " + String.join("、", GRANT_TYPES));
        }
        if (amount == null || amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "赠金额度须为正数（单位：分）");
        }
        if (months == null || months <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "赠金有效期须为正数（月）");
        }
        if ("CONSUME_THRESHOLD".equals(type) && (threshold == null || threshold <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消费满赠须设置门槛（分）且为正数");
        }
    }

    private boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private void audit(String bizType, String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record(bizType, txnNo, DataScope.currentActor(), action,
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload));
        } catch (Exception e) {
            audit.record(bizType, txnNo, DataScope.currentActor(), action, "{}");
        }
    }

    // ==================== 命令 DTO ====================

    public record CreateRuleCmd(
            String name,
            String grantType,
            Long thresholdFen,
            Long grantAmountFen,
            Integer expireMonths,
            String applicableStores,
            Integer priority) {}

    /**
     * 手工发赠金入参。
     *
     * @param idemKey 客户端业务幂等号（可选；不传服务端生成 UUID，建议传审批/补偿单号）
     */
    public record IssueCmd(String customerId, Long amountFen, Integer expireMonths, String idemKey) {}
}
