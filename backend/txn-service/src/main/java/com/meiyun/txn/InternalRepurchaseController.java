package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 复购预测候选信号投影（服务间内部端点，非业务页面，B47 卡2）。
 *
 * <p>与 B24 的 {@link InternalOrderController} 相互独立——仅新增只读投影，不修改任何既有文件。
 * 以系统身份（X-Internal-Token，perms=["*"]，复用 {@code internal:finance-flow}）向 ai-service
 * 输出按客户聚合的真实成交信号；事实全部来自 status='已收款' 的 txn_order，复用 {@link RfmCalculator}
 * 的读时 RFM 口径，不落库、不造数。普通登录人无此权限 → 403。
 *
 * <p>候选取最近成交的 {@code limit} 个客户（默认 8、上限 20），避免单次模型 prompt 超 4000 字。
 * 推荐项目取该客户最近一次真实成交订单的中文项目名（由 AI 侧规则归类品类），不由模型编造。
 */
@RestController
@RequestMapping("/api/txn/internal")
public class InternalRepurchaseController {

    private static final int SCAN_LIMIT = 500;
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;

    private final TxnOrderRepository orderRepo;
    private final RfmCalculator rfmCalculator;

    public InternalRepurchaseController(TxnOrderRepository orderRepo, RfmCalculator rfmCalculator) {
        this.orderRepo = orderRepo;
        this.rfmCalculator = rfmCalculator;
    }

    /**
     * 复购候选信号：GET /api/txn/internal/repurchase-candidates?storeCode=&limit=。
     * 按客户聚合已收款订单（RFM/平均成交间隔/最近项目与客单价），按最近成交时间倒序取前 limit。
     */
    @GetMapping("/repurchase-candidates")
    @RequirePerm("internal:finance-flow")
    public List<CandidateSignalView> candidates(
            @RequestParam(value = "storeCode", required = false) String storeCode,
            @RequestParam(value = "limit", required = false) Integer limit) {

        int take = Math.min(Math.max(limit == null ? DEFAULT_LIMIT : limit, 1), MAX_LIMIT);

        List<TxnOrder> recent = storeCode == null || storeCode.isBlank()
                ? orderRepo.findByStatusOrderByCreatedAtDesc("已收款", PageRequest.of(0, SCAN_LIMIT)).getContent()
                : orderRepo.findByStoreCodeAndStatusOrderByCreatedAtDesc(
                        storeCode.trim(), "已收款", PageRequest.of(0, SCAN_LIMIT)).getContent();
        if (recent.isEmpty()) {
            return List.of();
        }

        Map<String, List<TxnOrder>> byCustomer = new LinkedHashMap<>();
        for (TxnOrder o : recent) {
            String cid = o.getCustomerId();
            if (cid == null || cid.isBlank()) {
                continue;
            }
            byCustomer.computeIfAbsent(cid, k -> new ArrayList<>()).add(o);
        }

        List<CandidateSignalView> out = new ArrayList<>();
        for (Map.Entry<String, List<TxnOrder>> e : byCustomer.entrySet()) {
            if (out.size() >= take) {
                break;
            }
            List<TxnOrder> orders = e.getValue();
            TxnOrder latest = orders.stream()
                    .max(Comparator.comparing(TxnOrder::getCreatedAt,
                            Comparator.nullsFirst(OffsetDateTime::compareTo)))
                    .orElse(null);
            if (latest == null) {
                continue;
            }
            RfmCalculator.RfmView rfm = rfmCalculator.calc(orders, null);
            out.add(new CandidateSignalView(
                    e.getKey(),
                    latest.getStoreCode() == null ? "" : latest.getStoreCode(),
                    latest.getProject() == null ? "" : latest.getProject(),
                    latest.getAmount() == null ? 0L : latest.getAmount(),
                    avgTicketFen(orders),
                    rfm.totalOrders(),
                    rfm.freq365(),
                    rfm.monetary365(),
                    rfm.recencyDays(),
                    rfm.orders90(),
                    rfm.rScore(), rfm.fScore(), rfm.mScore(),
                    rfm.lifecycle(), rfm.segment(),
                    avgIntervalDays(orders)));
        }
        return out;
    }

    private long avgTicketFen(List<TxnOrder> orders) {
        long cents = 0;
        int n = 0;
        for (TxnOrder o : orders) {
            if (o.getAmount() != null) {
                cents += o.getAmount();
                n++;
            }
        }
        return n == 0 ? 0L : cents / n;
    }

    /** 相邻已收款订单平均间隔天数（按成交时间升序）；不足 2 单返回 null。 */
    private Long avgIntervalDays(List<TxnOrder> orders) {
        List<OffsetDateTime> times = orders.stream()
                .map(TxnOrder::getCreatedAt)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        if (times.size() < 2) {
            return null;
        }
        long total = 0;
        for (int i = 1; i < times.size(); i++) {
            total += Duration.between(times.get(i - 1), times.get(i)).toDays();
        }
        return total / (times.size() - 1);
    }

    /**
     * 单客户复购信号视图。金额单位：lastAmountFen/avgTicketFen 为「分」，monetary365Yuan 为「元」（RFM 口径）。
     */
    public record CandidateSignalView(
            String customerId, String storeCode, String lastProject,
            Long lastAmountFen, Long avgTicketFen,
            Long totalOrders, Integer freq365, Double monetary365Yuan,
            Long recencyDays, Integer orders90,
            Integer rScore, Integer fScore, Integer mScore,
            String lifecycle, String segment, Long avgIntervalDays) {
    }
}
