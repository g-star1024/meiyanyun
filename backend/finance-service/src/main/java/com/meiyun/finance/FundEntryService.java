package com.meiyun.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资金分部落账服务（B3 合规写核心）。
 *
 * <p>同事务内完成：校验 → 幂等查 idem_key → 写 fund_entry → 登记 outbox_record(PENDING) →
 * 联动 account_mirror（渠道账户）/ prepay_pool（预收池）→ audit 审计。
 * 分录口径与 {@link FinanceAggregationService} 读时聚合逐字对齐（双跑期 ledger-diff 零差异前提）：
 * <ul>
 *   <li>订单收款 ORDER → RF-REVENUE/IN/CASHIER，channel=收银渠道（wxpay/alipay/cash/card/balance）；</li>
 *   <li>退款 REFUND → RF-REFUND/OUT/CASHIER，channel=退款渠道；退卡另加 RF-DEPOSIT/OUT 冲预收；</li>
 *   <li>卡扣划扣 WRITEOFF → RF-DEPOSIT/OUT/ERP + RF-REVENUE/IN/ERP 成对，channel=null 内部结转；</li>
 *   <li>订单整单核销（cardNo 空）不投递分录（无新资金动账，避免双算）。</li>
 * </ul>
 */
@Service
public class FundEntryService {

    private static final Logger log = LoggerFactory.getLogger(FundEntryService.class);

    static final Set<String> SUBJECTS = Set.of(
            "RF-REVENUE", "RF-REFUND", "RF-DEPOSIT",
            "TK-MATERIAL", "TK-LOSS", "TK-DEPRECIATION", "TK-LABOR");
    static final Set<String> DIRECTIONS = Set.of("IN", "OUT");
    static final Set<String> CHANNELS = Set.of("cash", "card", "wxpay", "alipay", "balance", "transfer");
    static final Set<String> BIZ_TYPES = Set.of("ORDER", "REFUND", "WRITEOFF", "RECHARGE", "ADJUST", "COST");

    /** B5 成本科目 → 成本类型映射（cost_allocation.cost_type）。 */
    private static final Map<String, String> TK_TO_COST_TYPE = Map.of(
            "TK-MATERIAL", "MATERIAL",
            "TK-LOSS", "LOSS",
            "TK-DEPRECIATION", "DEPRECIATION",
            "TK-LABOR", "LABOR");

    private final FundEntryRepository entryRepo;
    private final OutboxRepository outboxRepo;
    private final AccountMirrorRepository acctRepo;
    private final PrepayPoolRepository poolRepo;
    private final CostAllocationRepository costRepo;
    private final RevenueMonthlyRepository revRepo;
    private final FinanceAuditRecorder audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FundEntryService(FundEntryRepository entryRepo, OutboxRepository outboxRepo,
                            AccountMirrorRepository acctRepo, PrepayPoolRepository poolRepo,
                            CostAllocationRepository costRepo, RevenueMonthlyRepository revRepo,
                            FinanceAuditRecorder audit) {
        this.entryRepo = entryRepo;
        this.outboxRepo = outboxRepo;
        this.acctRepo = acctRepo;
        this.poolRepo = poolRepo;
        this.costRepo = costRepo;
        this.revRepo = revRepo;
        this.audit = audit;
    }

    /** 批量落账（逐条幂等：idem_key 已存在则跳过并回带已落账结果），返回逐条结果。 */
    @Transactional
    public List<Map<String, Object>> postEntries(List<FundEntryCmd> cmds, String actor) {
        if (cmds == null || cmds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "分录列表不能为空");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (FundEntryCmd cmd : cmds) {
            results.add(postOne(cmd, actor));
        }
        return results;
    }

    private Map<String, Object> postOne(FundEntryCmd cmd, String actor) {
        validate(cmd);
        String idemKey = cmd.idemKey() != null && !cmd.idemKey().isBlank()
                ? cmd.idemKey()
                : cmd.bizType() + ":" + cmd.bizRef() + ":" + cmd.subject() + ":" + cmd.direction();

        FundEntry existing = entryRepo.findByIdemKey(idemKey).orElse(null);
        if (existing != null) {
            // 幂等重放：不双算，回带已落账分录
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("idemKey", idemKey);
            r.put("entryId", existing.getEntryId());
            r.put("duplicated", true);
            r.put("message", "分录已落账，幂等跳过");
            return r;
        }

        OffsetDateTime occurred = parseOccurred(cmd.occurredAt());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        FundEntry e = new FundEntry();
        e.setIdemKey(idemKey);
        e.setBizRef(cmd.bizRef());
        e.setBizType(cmd.bizType());
        e.setSubject(cmd.subject());
        e.setDirection(cmd.direction());
        e.setAmount(cmd.amount());
        e.setChannel(normalizeChannel(cmd.channel()));
        e.setSource(cmd.source());
        e.setRefType(cmd.refType() != null && !cmd.refType().isBlank() ? cmd.refType() : cmd.bizType());
        e.setStoreCode(cmd.storeCode());
        e.setMemo(cmd.memo());
        e.setOccurredAt(occurred);
        e.setCreatedAt(now);
        entryRepo.save(e);

        // outbox 对账台账：逐笔 PENDING，净额方向（IN 正 / OUT 负）
        OutboxRecord rec = new OutboxRecord();
        rec.setBizType(cmd.bizType());
        rec.setTxnNo(cmd.bizRef());
        rec.setAmount(signedAmount(cmd.subject(), cmd.direction(), cmd.amount()));
        rec.setChannel(e.getChannel());
        rec.setStatus("PENDING");
        rec.setCreatedAt(now);
        outboxRepo.save(rec);

        // 镜像联动
        applyAccountMirror(e);
        applyPrepayPool(e);

        // 审计（payload 为合法 JSON 字符串，统一 ObjectMapper 序列化防注入）
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("idemKey", idemKey);
        payloadMap.put("bizRef", cmd.bizRef());
        payloadMap.put("bizType", cmd.bizType());
        payloadMap.put("subject", cmd.subject());
        payloadMap.put("direction", cmd.direction());
        payloadMap.put("amountFen", cmd.amount());
        payloadMap.put("channel", e.getChannel());
        payloadMap.put("source", cmd.source());
        payloadMap.put("storeCode", cmd.storeCode());
        audit.record("FUND_ENTRY", cmd.bizRef(), actor != null ? actor : "system", "POST", toJson(payloadMap));

        log.info("资金分部落账 idemKey={} {} {}/{} {} 分 store={}",
                idemKey, cmd.bizRef(), cmd.subject(), cmd.direction(), cmd.amount(), cmd.storeCode());

        // B5 成本域联动：TK-* 成本科目同写 cost_allocation；任一分录落账后重算该店该月月报（收入/成本/毛利/两率）
        applyCostAllocation(e, now);
        LocalDate periodMonth = monthOf(occurred);
        refreshRevenueMonthly(cmd.storeCode(), periodMonth);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("idemKey", idemKey);
        r.put("entryId", e.getEntryId());
        r.put("outboxId", rec.getOutboxId());
        r.put("duplicated", false);
        r.put("message", "落账成功");
        return r;
    }

    // ==================== 校验 ====================

    private void validate(FundEntryCmd c) {
        if (c == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "分录不能为空");
        if (isBlank(c.bizRef())) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "业务单据号 bizRef 不能为空");
        if (isBlank(c.bizType()) || !BIZ_TYPES.contains(c.bizType()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "业务类型 bizType 非法，允许值：ORDER/REFUND/WRITEOFF/RECHARGE/ADJUST/COST");
        if (isBlank(c.subject()) || !SUBJECTS.contains(c.subject()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "科目 subject 非法，允许值：RF-REVENUE/RF-REFUND/RF-DEPOSIT（资金类）、TK-MATERIAL/TK-LOSS/TK-DEPRECIATION/TK-LABOR（成本类）");
        if (isBlank(c.direction()) || !DIRECTIONS.contains(c.direction()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "方向 direction 非法，允许值：IN/OUT");
        if (c.amount() == null || c.amount() <= 0)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "金额 amount 必须为正数（单位：分）");
        if (isBlank(c.source()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "来源 source 不能为空（CASHIER 收银 / ERP 内部结转 / MANUAL 人工调平）");
        if (isBlank(c.storeCode()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "门店码 storeCode 不能为空");
        if (c.channel() != null && !c.channel().isBlank() && !CHANNELS.contains(normalizeChannel(c.channel())))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "渠道 channel 非法，允许值：cash/card/wxpay/alipay/balance/transfer");
        if (!isBlank(c.occurredAt())) parseOccurred(c.occurredAt()); // 提前校验时间格式
    }

    private OffsetDateTime parseOccurred(String s) {
        if (isBlank(s)) return OffsetDateTime.now(ZoneOffset.UTC);
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "发生时间 occurredAt 格式非法，需 ISO-8601（如 2026-09-05T10:00:00+08:00）");
        }
    }

    private String normalizeChannel(String ch) {
        return isBlank(ch) ? null : ch.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** outbox 净额方向：IN 记正、OUT 记负（RF-DEPOSIT/OUT、RF-REFUND/OUT 为流出）。 */
    private long signedAmount(String subject, String direction, long amount) {
        return "IN".equals(direction) ? amount : -amount;
    }

    // ==================== 镜像联动 ====================

    /** 渠道账户镜像：仅 CASHIER（收银实际进出商户户）分录联动；ERP 内部结转不动渠道账户。 */
    private void applyAccountMirror(FundEntry e) {
        if (!"CASHIER".equals(e.getSource()) || e.getChannel() == null) return;
        String acctId = acctIdOf(e.getChannel());
        if (acctId == null) return; // balance/transfer 等非渠道账户，不联动渠道镜像
        AccountMirror acct = acctRepo.findById(acctId).orElseGet(() -> {
            AccountMirror a = new AccountMirror();
            a.setAcctId(acctId);
            a.setAcctName(acctNameOf(e.getChannel()));
            a.setAcctType(acctTypeOf(e.getChannel()));
            a.setBalance(0L);
            return a;
        });
        long delta = "IN".equals(e.getDirection()) ? e.getAmount() : -e.getAmount();
        acct.setBalance(acct.getBalance() + delta);
        acctRepo.save(acct);
    }

    /** 渠道码 → 账户镜像映射（acct_type 受中文 CHECK 约束：对公活期/支付宝商户/微信商户）。 */
    private String acctIdOf(String channel) {
        return switch (channel) {
            case "wxpay" -> "ACCT-WX";
            case "alipay" -> "ACCT-ALI";
            case "cash", "card", "transfer" -> "ACCT-CASH"; // 现金/刷卡/转账并入对公活期
            default -> null;
        };
    }

    private String acctNameOf(String channel) {
        return switch (channel) {
            case "wxpay" -> "微信商户";
            case "alipay" -> "支付宝商户";
            default -> "对公活期";
        };
    }

    private String acctTypeOf(String channel) {
        return switch (channel) {
            case "wxpay" -> "微信商户";
            case "alipay" -> "支付宝商户";
            default -> "对公活期";
        };
    }

    /**
     * 预收池联动（仅预收类科目 RF-DEPOSIT；B3 覆盖划扣预收转出，退卡冲回 B3 同批投递）：
     * RF-DEPOSIT/OUT = 预收转收入/冲回 → total 与 pendingConsume 同减；
     * RF-DEPOSIT/IN（充值预收，B4）→ total 与 pendingConsume 同增。
     * 恒等式 total = pendingConsume + refundable + earnedPending 始终保持。
     */
    private void applyPrepayPool(FundEntry e) {
        if (!"RF-DEPOSIT".equals(e.getSubject())) return;
        PrepayPool pool = poolRepo.findById(1).orElseGet(() -> {
            PrepayPool p = new PrepayPool();
            p.setPoolId(1);
            p.setTotal(0L);
            p.setPendingConsume(0L);
            p.setRefundable(0L);
            p.setEarnedPending(0L);
            return p;
        });
        long delta = "IN".equals(e.getDirection()) ? e.getAmount() : -e.getAmount();
        long newTotal = pool.getTotal() + delta;
        long newPending = pool.getPendingConsume() + delta;
        if (newTotal < 0 || newPending < 0) {
            // 预收池未回填/被异常冲减时不允许违反 CHECK，钳 0 并告警（双跑期以告警暴露口径差异）
            log.warn("预收池联动出现负余额，已钳 0 并保留告警：bizRef={} {} {} delta={} total={} pending={}",
                    e.getBizRef(), e.getSubject(), e.getDirection(), delta, pool.getTotal(), pool.getPendingConsume());
            newTotal = Math.max(0, newTotal);
            newPending = Math.max(0, newPending);
        }
        pool.setTotal(newTotal);
        pool.setPendingConsume(newPending);
        poolRepo.save(pool);
    }

    // ==================== B5 成本域 ====================

    /**
     * 人工成本录入/分摊（POST /api/finance/cost-allocation，finance:cost:edit）：
     * 期末录入折旧（DEPRECIATION）、人工（LABOR）。同事务落 fund_entry（TK 成本科目，OUT/COST/MANUAL），
     * 由落账钩子同写 cost_allocation 并重算月报。耗材（MATERIAL）/报损（LOSS）由领用/报损终审自动落账，
     * 人工录入会干扰库存口径，故拒绝。
     */
    @Transactional
    public Map<String, Object> recordManualCost(String costType, String storeCode, LocalDate month,
                                                Long amountFen, String memo, String actor) {
        String subject;
        if ("DEPRECIATION".equals(costType)) {
            subject = "TK-DEPRECIATION";
        } else if ("LABOR".equals(costType)) {
            subject = "TK-LABOR";
        } else {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "人工仅支持录入折旧（DEPRECIATION）/人工（LABOR）成本；耗材（MATERIAL）/报损（LOSS）由审批通过自动落账");
        }
        if (isBlank(storeCode)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "门店码 storeCode 不能为空");
        }
        if (month == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "归属月份 month 不能为空（yyyy-MM-dd，建议传每月 1 号）");
        }
        if (amountFen == null || amountFen <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "金额 amountFen 必须为正数（单位：分）");
        }
        LocalDate periodMonth = month.withDayOfMonth(1);
        String label = "TK-DEPRECIATION".equals(subject) ? "折旧分摊" : "人工成本";
        String costRef = nextCostRef();
        String useMemo = label + " · " + periodMonth + (isBlank(memo) ? "" : " · " + truncate(memo, 60));
        // occurredAt 落在归属月月中（15 日 12:00 UTC），避免时区跨月
        OffsetDateTime occurred = periodMonth.withDayOfMonth(15).atTime(12, 0).atOffset(ZoneOffset.UTC);
        String idemKey = "COST:" + costRef;
        FundEntryCmd cmd = new FundEntryCmd(idemKey, costRef, "COST", subject, "OUT", amountFen,
                null, "MANUAL", "COST", storeCode, useMemo, occurred.toString());
        List<Map<String, Object>> posted = postEntries(List.of(cmd), actor);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("costRef", costRef);
        payload.put("costType", costType);
        payload.put("storeCode", storeCode);
        payload.put("periodMonth", periodMonth.toString());
        payload.put("amountFen", amountFen);
        payload.put("memo", memo);
        audit.record("COST_ALLOCATION", costRef, actor != null ? actor : "system", "MANUAL", toJson(payload));

        log.info("人工成本录入 costRef={} {} {} {} 分 actor={}", costRef, costType, storeCode, amountFen, actor);

        Map<String, Object> r = new LinkedHashMap<>(posted.get(0));
        r.put("costRef", costRef);
        r.put("periodMonth", periodMonth.toString());
        r.put("costType", costType);
        return r;
    }

    /** 人工成本单号：COST + yyyyMMdd + '-' + 6 位序号（按北京时间当日递增）。 */
    private synchronized String nextCostRef() {
        String day = LocalDate.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "COST" + day + "-";
        int seq = costRepo.maxSeqOfDay(prefix + "%") + 1;
        return prefix + String.format("%06d", seq);
    }

    /**
     * 成本分录同写成本分摊表：仅 TK-* 成本科目且 bizType=COST 联动；
     * sourceRef=bizRef（领用/报损=审批单号 todoNo；人工=COST 单号）。
     * 幂等由 fund_entry idem_key 保证（重放整体在 postOne 前置跳过，不会执行到这里）。
     */
    private void applyCostAllocation(FundEntry e, OffsetDateTime now) {
        String costType = TK_TO_COST_TYPE.get(e.getSubject());
        if (costType == null || !"COST".equals(e.getBizType())) return;
        CostAllocation c = new CostAllocation();
        c.setPeriodMonth(monthOf(e.getOccurredAt()));
        c.setStoreCode(e.getStoreCode());
        c.setCostType(costType);
        c.setAmount(e.getAmount());
        c.setSourceRef(e.getBizRef());
        c.setCreatedAt(now);
        costRepo.save(c);
    }

    /** UTC 月份归属（与 FinanceAggregationService 日界 UTC 口径一致），归一到每月 1 号。 */
    private LocalDate monthOf(OffsetDateTime t) {
        return t.atZoneSameInstant(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    }

    /**
     * 重算单店单月月报：收入 = RF-REVENUE/IN − RF-REVENUE/OUT（划扣冲回/调平）− RF-REFUND/OUT；
     * 成本 = 当月 cost_allocation 四类合计；毛利 = 收入 − 成本；两率 = cost/revenue、gross/revenue
     * （BigDecimal 6,3；收入 ≤ 0 时两率记 0）。该店该月无任何分录与成本时不建行（避免空行噪声）。
     */
    private void refreshRevenueMonthly(String storeCode, LocalDate periodMonth) {
        OffsetDateTime from = periodMonth.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime to = periodMonth.plusMonths(1).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        long revenue = 0;
        for (FundEntry e : entryRepo.findByOccurredAtBetweenOrderByOccurredAtAsc(from, to)) {
            if (!storeCode.equals(e.getStoreCode())) continue;
            if ("RF-REVENUE".equals(e.getSubject())) {
                revenue += "IN".equals(e.getDirection()) ? e.getAmount() : -e.getAmount();
            } else if ("RF-REFUND".equals(e.getSubject()) && "OUT".equals(e.getDirection())) {
                revenue -= e.getAmount();
            }
        }
        long cost = 0;
        for (CostAllocation c : costRepo.findByPeriodMonthOrderByStoreCodeAscCostIdAsc(periodMonth)) {
            if (storeCode.equals(c.getStoreCode())) cost += c.getAmount();
        }
        if (revenue == 0 && cost == 0) return;
        long gross = revenue - cost;
        BigDecimal zero3 = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        BigDecimal costRate = revenue > 0
                ? BigDecimal.valueOf(cost).divide(BigDecimal.valueOf(revenue), 3, RoundingMode.HALF_UP) : zero3;
        BigDecimal grossRate = revenue > 0
                ? BigDecimal.valueOf(gross).divide(BigDecimal.valueOf(revenue), 3, RoundingMode.HALF_UP) : zero3;

        RevenueMonthly m = revRepo.findById(new RevenueMonthly.RevenueMonthlyId(storeCode, periodMonth))
                .orElseGet(() -> {
                    RevenueMonthly x = new RevenueMonthly();
                    x.setStoreCode(storeCode);
                    x.setPeriodMonth(periodMonth);
                    return x;
                });
        m.setRevenue(revenue);
        m.setCost(cost);
        m.setGrossProfit(gross);
        m.setCostRate(costRate);
        m.setGrossRate(grossRate);
        revRepo.save(m);
        log.info("月报重算 store={} month={} revenue={} cost={} gross={}", storeCode, periodMonth, revenue, cost, gross);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ==================== 对账 / 调平（outbox 状态机） ====================

    /**
     * 人工/系统对账：PENDING → RECONCILED（对平）或 DIFF（差异，待调平）。
     * 非 PENDING 状态 400 中文（状态机不允许跳跃/重复对账）。
     */
    @Transactional
    public Map<String, Object> reconcileOutbox(Long outboxId, boolean diff, String remark, String actor) {
        OutboxRecord rec = outboxRepo.findById(outboxId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "对账台账记录不存在：outboxId=" + outboxId));
        if (!"PENDING".equals(rec.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "当前状态「" + rec.getStatus() + "」不可对账：仅待对账（PENDING）记录可标记对账结果");
        }
        String before = rec.getStatus();
        rec.setStatus(diff ? "DIFF" : "RECONCILED");
        rec.setReconciledAt(OffsetDateTime.now(ZoneOffset.UTC));
        outboxRepo.save(rec);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outboxId", outboxId);
        payload.put("txnNo", rec.getTxnNo());
        payload.put("amountFen", rec.getAmount());
        payload.put("before", before);
        payload.put("after", rec.getStatus());
        payload.put("remark", remark);
        audit.record("FUND_RECONCILE", rec.getTxnNo(), actor != null ? actor : "system",
                diff ? "MARK_DIFF" : "RECONCILE", toJson(payload));

        log.info("对账完成 outboxId={} {} → {} actor={}", outboxId, before, rec.getStatus(), actor);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("outboxId", outboxId);
        r.put("statusBefore", before);
        r.put("statusAfter", rec.getStatus());
        r.put("message", diff ? "已标记为差异，待调平" : "对账一致");
        return r;
    }

    /**
     * 差异调平：DIFF → ADJUSTED，补一条 ADJUST 调整分录（幂等：同 outbox+方向+科目重复调平返回已存在）。
     * 调平分录 source=MANUAL 不联动渠道镜像；科目为 RF-DEPOSIT 时按规则联动预收池（池差异调平）。
     */
    @Transactional
    public Map<String, Object> adjustOutbox(Long outboxId, String direction, Long amountFen,
                                            String subject, String channel, String memo, String actor) {
        OutboxRecord rec = outboxRepo.findById(outboxId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "对账台账记录不存在：outboxId=" + outboxId));
        if (!"DIFF".equals(rec.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "当前状态「" + rec.getStatus() + "」不可调平：仅差异（DIFF）记录可执行调平");
        }
        String useSubject = isBlank(subject) ? "RF-REVENUE" : subject;
        String useMemo = isBlank(memo) ? "差异调平 outboxId=" + outboxId : memo;
        String idemKey = "ADJUST:OUTBOX-" + outboxId + ":" + useSubject + ":" + direction;

        FundEntryCmd cmd = new FundEntryCmd(idemKey, "OUTBOX-" + outboxId, "ADJUST", useSubject,
                direction, amountFen, normalizeChannel(channel), "MANUAL", "ADJUST",
                "SYS00", useMemo, null);
        List<Map<String, Object>> posted = postEntries(List.of(cmd), actor);
        Map<String, Object> entryResult = posted.get(0);

        String before = rec.getStatus();
        rec.setStatus("ADJUSTED");
        rec.setReconciledAt(OffsetDateTime.now(ZoneOffset.UTC));
        outboxRepo.save(rec);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outboxId", outboxId);
        payload.put("txnNo", rec.getTxnNo());
        payload.put("before", before);
        payload.put("after", "ADJUSTED");
        payload.put("adjustSubject", useSubject);
        payload.put("adjustDirection", direction);
        payload.put("adjustAmountFen", amountFen);
        payload.put("idemKey", idemKey);
        audit.record("FUND_ADJUST", rec.getTxnNo(), actor != null ? actor : "system", "ADJUST", toJson(payload));

        log.info("差异调平完成 outboxId={} 补分录 {} {}/{} {} 分 actor={}",
                outboxId, useSubject, direction, amountFen, actor);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("outboxId", outboxId);
        r.put("statusBefore", before);
        r.put("statusAfter", "ADJUSTED");
        r.put("entry", entryResult);
        r.put("message", "调平完成，差异已补充分录");
        return r;
    }

    private String toJson(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            log.error("审计 payload JSON 序列化失败: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "审计内容序列化失败");
        }
    }
}
