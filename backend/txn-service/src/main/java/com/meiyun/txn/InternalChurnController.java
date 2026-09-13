package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 流失预警候选信号投影（服务间内部端点，非业务页面，B47 卡3）。
 *
 * <p>与 {@link InternalRepurchaseController} 相互独立——仅新增只读投影，不修改任何既有文件。
 * 复购候选取「最近成交」客户，流失候选恰好相反：扫最近 {@code SCAN_LIMIT} 笔 status='已收款' 订单
 * 按客户聚合后，按距上次成交天数倒序（久未到店优先）、再按近 365 天消费额倒序（高价值优先）取前
 * {@code limit} 个客户，复用 {@link RfmCalculator} 读时 RFM 口径，并增加近 90 天对比再前 90 天的
 * 消费下降率；事实全部来自真实成交订单，不落库、不造数。以系统身份（X-Internal-Token，复用
 * {@code internal:finance-flow}）向 ai-service 开放，普通登录人无此权限 → 403。
 *
 * <p>已知口径边界：候选宇宙来自最近 {@code SCAN_LIMIT} 笔成交的客户聚合，超窗的极久未成交客户
 * （在最近成交序列中已无任何订单）不在候选内；演示/单店规模下该窗口足以覆盖全量成交客户。
 */
@RestController
@RequestMapping("/api/txn/internal")
public class InternalChurnController {

    private static final int SCAN_LIMIT = 3000;
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TxnOrderRepository orderRepo;
    private final RfmCalculator rfmCalculator;

    public InternalChurnController(TxnOrderRepository orderRepo, RfmCalculator rfmCalculator) {
        this.orderRepo = orderRepo;
        this.rfmCalculator = rfmCalculator;
    }

    /**
     * 流失候选信号：GET /api/txn/internal/churn-candidates?storeCode=&limit=。
     * 按客户聚合已收款订单（RFM/到店间隔/消费下降率/最近成交日期），按久未到店优先取前 limit。
     */
    @GetMapping("/churn-candidates")
    @RequirePerm("internal:finance-flow")
    public List<ChurnSignalView> candidates(
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

        List<ChurnSignalView> all = new ArrayList<>();
        for (Map.Entry<String, List<TxnOrder>> e : byCustomer.entrySet()) {
            List<TxnOrder> orders = e.getValue();
            TxnOrder latest = orders.stream()
                    .max(Comparator.comparing(TxnOrder::getCreatedAt,
                            Comparator.nullsFirst(OffsetDateTime::compareTo)))
                    .orElse(null);
            if (latest == null || latest.getCreatedAt() == null) {
                continue;
            }
            RfmCalculator.RfmView rfm = rfmCalculator.calc(orders, null);
            long recency = rfm.recencyDays() == null ? 0L : rfm.recencyDays();
            long spend90 = sumAmountSince(orders, 90);
            long spendPrev90 = sumAmountBetween(orders, 90, 180);
            Integer decline = spendPrev90 <= 0 ? null
                    : (int) Math.max(0, Math.min(100,
                            Math.round((spendPrev90 - spend90) * 100.0 / spendPrev90)));
            all.add(new ChurnSignalView(
                    e.getKey(),
                    latest.getStoreCode() == null ? "" : latest.getStoreCode(),
                    latest.getCreatedAt().format(DATE_FMT),
                    latest.getProject() == null ? "" : latest.getProject(),
                    rfm.totalOrders(), rfm.freq365(), rfm.monetary365(),
                    recency, rfm.orders90(),
                    rfm.rScore(), rfm.fScore(), rfm.mScore(),
                    rfm.lifecycle(), rfm.segment(),
                    spend90, spendPrev90, decline));
        }

        all.sort(Comparator
                .comparingLong(ChurnSignalView::recencyDays).reversed()
                .thenComparing(Comparator.comparingDouble(ChurnSignalView::monetary365Yuan).reversed()));
        return all.size() <= take ? all : new ArrayList<>(all.subList(0, take));
    }

    private long sumAmountSince(List<TxnOrder> orders, int days) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(days);
        long cents = 0;
        for (TxnOrder o : orders) {
            if (o.getCreatedAt() != null && !o.getCreatedAt().isBefore(since) && o.getAmount() != null) {
                cents += o.getAmount();
            }
        }
        return cents;
    }

    private long sumAmountBetween(List<TxnOrder> orders, int fromDays, int toDays) {
        OffsetDateTime from = OffsetDateTime.now().minusDays(fromDays);
        OffsetDateTime to = OffsetDateTime.now().minusDays(toDays);
        long cents = 0;
        for (TxnOrder o : orders) {
            if (o.getCreatedAt() != null && o.getCreatedAt().isBefore(from)
                    && !o.getCreatedAt().isBefore(to) && o.getAmount() != null) {
                cents += o.getAmount();
            }
        }
        return cents;
    }

    /**
     * 单客户流失信号视图。金额单位：spend90Fen/spendPrev90Fen 为「分」，monetary365Yuan 为「元」（RFM 口径）。
     */
    public record ChurnSignalView(
            String customerId, String storeCode, String lastVisitDate, String lastProject,
            Long totalOrders, Integer freq365, Double monetary365Yuan,
            Long recencyDays, Integer orders90,
            Integer rScore, Integer fScore, Integer mScore,
            String lifecycle, String segment,
            Long spend90Fen, Long spendPrev90Fen, Integer spendDeclinePct) {
    }
}
