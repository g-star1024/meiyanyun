package com.meiyun.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 消费满额自动发赠金引擎（B37 卡2，ROADMAP B35 新增行）。
 *
 * <p>定时任务 {@link AutoGrantJob} 驱动：轮询 txn-service 内部只读投影「已收款订单」，
 * 对每笔非售卡消费单按启用中的 CONSUME_THRESHOLD 规则做门槛/门店匹配，命中即调
 * {@link GrantService#issueByRule} 发放赠金。与 B25 消费自动积分复用同一事件源
 *（/api/txn/internal/paid-orders），但幂等命名空间独立（积分 AUTOPOINTS: vs 赠金 RULE:），互不干扰。
 *
 * <p>规则匹配口径（grant_rule 现有字段，无阶梯）：
 * <ul>
 *   <li>仅 status=ENABLED 且 grantType=CONSUME_THRESHOLD 参与，按 priority 升序，<b>单笔订单命中首条即止</b>
 *       （优先级即赠金力度次序，一单不叠加多档赠金）；</li>
 *   <li>门槛：order.amount(分) ≥ rule.thresholdFen；</li>
 *   <li>门店：applicableStores 空串=全部门店，否则逗号串须包含订单 storeCode。</li>
 * </ul>
 *
 * <p>双层幂等：{@code idem_key=RULE:{ruleId}:{orderNo}} + customer_grant.uk_cg_idem 防同规则重放；
 * {@code existsBySourceBizRef(orderNo)} 锚定「一笔已收款订单至多自动发赠一次」，
 * 防运营中途切换规则后旧单被新规则二次发放。邻轮窗口按日粒度重叠，重复出现的订单一律静默吞掉。
 *
 * <p>事务边界复刻 AutoPointsService：scan 本身不包大事务，每笔 issueByRule 各自独立事务提交，
 * 单笔失败（客户不存在 400）只跳过告警；客户域不可用（502）等基础设施故障则整轮中止、
 * <b>不推进游标</b>，下轮按原窗口自愈。售卡/充值单（bizKind=CARD_SALE）不参与满赠，防充值循环套利。
 *
 * <p>范围边界：本引擎只做「收款触发发放」；退款回加（REFUND 反向流水）GrantService 已标注
 * 下游 Backlog——退款时赠金可能已抵扣/已过期，回加语义需交易退款链路专项定义，本批不做。
 */
@Service
public class AutoGrantService {

    private static final Logger log = LoggerFactory.getLogger(AutoGrantService.class);

    private final TxnInternalClient txnClient;
    private final GrantRuleRepository ruleRepo;
    private final CustomerGrantRepository grantRepo;
    private final GrantService grantService;
    private final AutoGrantStateRepository stateRepo;

    public AutoGrantService(TxnInternalClient txnClient, GrantRuleRepository ruleRepo,
                            CustomerGrantRepository grantRepo, GrantService grantService,
                            AutoGrantStateRepository stateRepo) {
        this.txnClient = txnClient;
        this.ruleRepo = ruleRepo;
        this.grantRepo = grantRepo;
        this.grantService = grantService;
        this.stateRepo = stateRepo;
    }

    /** 单次扫描结果。error 非空表示基础设施故障、游标未推进（下轮重试）。 */
    public record ScanResult(long scanned, long granted, String error) {}

    /**
     * 执行一次满赠扫描：窗口 = (last_run_at, now]，日粒度格式化（邻轮重叠靠幂等去重）。
     * 成功处理完全部订单后把 last_run_at 推进到本轮上界；txn/客户域故障返回 error 且不推进。
     */
    public ScanResult scan() {
        OffsetDateTime now = OffsetDateTime.now();
        AutoGrantState state = stateRepo.findById(1).orElseGet(() -> {
            AutoGrantState s = new AutoGrantState();
            s.setStateId(1);
            return s;
        });
        // 首次（NULL）→ 远早于系统的下界全量回填；是否实际发放取决于库内有无启用的满赠规则
        OffsetDateTime from = state.getLastRunAt() == null
                ? OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                : state.getLastRunAt();
        String fromStr = from.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String toStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE);

        long scanned = 0;
        long granted = 0;
        try {
            List<GrantRule> rules = ruleRepo.findByStatusOrderByPriorityAsc("ENABLED").stream()
                    .filter(r -> "CONSUME_THRESHOLD".equals(r.getGrantType()))
                    .filter(r -> r.getThresholdFen() != null && r.getThresholdFen() > 0)
                    .toList();

            List<TxnInternalClient.PaidOrder> paid;
            try {
                paid = txnClient.fetchPaidOrders(fromStr, toStr);
            } catch (TxnServiceUnavailableException ex) {
                return new ScanResult(0, 0, ex.getMessage());
            }

            for (TxnInternalClient.PaidOrder o : paid) {
                scanned++;
                if (o.orderNo() == null || o.orderNo().isBlank()) continue;
                if (o.customerId() == null || o.customerId().isBlank()) continue;
                if (o.amount() == null || o.amount() <= 0) continue;
                // 售卡/充值单不计消费满赠（与自动积分同口径），防充值循环套利
                if ("CARD_SALE".equals(o.bizKind())) continue;
                if (rules.isEmpty()) continue;
                GrantRule hit = matchRule(rules, o);
                if (hit == null) continue;
                // 订单级去重：该单已被任何自动规则发放过（含跨规则切换）则静默跳过
                if (grantRepo.existsBySourceBizRef(o.orderNo())) continue;
                try {
                    grantService.issueByRule(o.customerId(), hit, o.orderNo());
                    granted++;
                } catch (ResponseStatusException ex) {
                    if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
                        // 客户不存在（已删/脏数据）→ 跳过该单，不毒化整批；游标照常推进，下轮靠幂等自然收敛
                        log.warn("自动发赠金跳过（客户不存在）：orderNo={} customer={}",
                                o.orderNo(), o.customerId());
                    } else {
                        // 客户域不可用 502 / 冲突 409 等 → 整轮中止，不推进游标，下轮重试
                        return new ScanResult(scanned, granted,
                                "发放中止（依赖域故障）：orderNo=" + o.orderNo() + " " + ex.getReason());
                    }
                }
            }

            state.setLastRunAt(now);
            state.setLastScanned(scanned);
            state.setLastGranted(granted);
            stateRepo.save(state);
        } catch (Exception ex) {
            log.error("消费满额自动发赠金扫描异常", ex);
            return new ScanResult(scanned, granted, "扫描异常：" + ex.getMessage());
        }
        return new ScanResult(scanned, granted, null);
    }

    /**
     * 按 priority 升序返回首条命中规则：门槛达标且门店在适用范围内；无命中返回 null。
     */
    private GrantRule matchRule(List<GrantRule> rules, TxnInternalClient.PaidOrder o) {
        for (GrantRule r : rules) {
            if (o.amount() < r.getThresholdFen()) continue;
            if (!storeMatches(r.getApplicableStores(), o.storeCode())) continue;
            return r;
        }
        return null;
    }

    /** 适用门店为空串/空白=全部门店；否则逗号串须精确包含订单门店码（订单无门店码则限定类规则不命中）。 */
    private boolean storeMatches(String applicableStores, String orderStoreCode) {
        if (applicableStores == null || applicableStores.isBlank()) return true;
        if (orderStoreCode == null || orderStoreCode.isBlank()) return false;
        for (String code : applicableStores.split(",")) {
            if (orderStoreCode.equals(code.trim())) return true;
        }
        return false;
    }
}
