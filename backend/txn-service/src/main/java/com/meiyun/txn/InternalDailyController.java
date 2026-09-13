package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 经营日报单日经营指标投影（服务间内部端点，非业务页面，B47 卡4）。
 *
 * <p>与 {@link InternalChurnController} / {@link InternalOrderController} 相互独立——仅新增只读投影，
 * 不修改任何既有文件。面向 ai-service 经营日报生成，以系统身份（X-Internal-Token，复用
 * {@code internal:finance-flow}）取回，普通登录人无此权限 → 403；不做 DataScope 门店域收敛，
 * 由 ai 侧按登录人门店域二次过滤。
 *
 * <p>日界取 Asia/Shanghai 自然日（与 {@link InternalFinanceController} 的 /cash-settle 同口径）：
 * <ul>
 *   <li>营收：当日 status='已收款' 订单笔数与金额合计（分），金额口径与收款一致；</li>
 *   <li>到店：arrival 表当日 arrivedAt 落自然日的登记行数（不去重，与候诊队列登记一致）；</li>
 *   <li>新客：当日已收款客户中，历史首笔已收款订单即落在当日的客户数（真实首单判定，非埋点）；</li>
 *   <li>异常：当日已完成退款（status='REFUNDED'，按 refunded_at）笔数与金额，加上当日已收款订单
 *       contraCheck=YELLOW/RED 的风险单据笔数，供日报「异常项」如实取数。</li>
 * </ul>
 * 新客判定窗口取最近 {@link #SCAN_LIMIT} 笔已收款订单（同流失候选扫描窗口），演示/单店规模足以覆盖
 * 全量成交客户；不落库、不造数，零数据时各指标为 0 而非缺省。
 */
@RestController
@RequestMapping("/api/txn/internal")
public class InternalDailyController {

    private static final ZoneId BJ = ZoneId.of("Asia/Shanghai");
    private static final int SCAN_LIMIT = 3000;

    private final TxnOrderRepository orderRepo;
    private final ArrivalRepository arrivalRepo;
    private final TxnRefundRepository refundRepo;

    public InternalDailyController(TxnOrderRepository orderRepo,
                                   ArrivalRepository arrivalRepo,
                                   TxnRefundRepository refundRepo) {
        this.orderRepo = orderRepo;
        this.arrivalRepo = arrivalRepo;
        this.refundRepo = refundRepo;
    }

    /**
     * 单日经营指标：GET /api/txn/internal/daily-metrics?date=2026-09-13&amp;storeCode=SST01。
     * 金额单位 Long「分」；date 缺省取北京当日，非法日期 400 中文提示。
     */
    @GetMapping("/daily-metrics")
    @RequirePerm("internal:finance-flow")
    public DailyMetricsView dailyMetrics(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "storeCode", required = false) String storeCode) {

        LocalDate day;
        try {
            day = (date == null || date.isBlank()) ? LocalDate.now(BJ) : LocalDate.parse(date.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "日期参数 date 格式非法，需 yyyy-MM-dd（如 2026-09-13）：" + date);
        }
        String store = (storeCode == null || storeCode.isBlank()) ? null : storeCode.trim();
        OffsetDateTime start = day.atStartOfDay(BJ).toOffsetDateTime();
        OffsetDateTime end = day.plusDays(1).atStartOfDay(BJ).toOffsetDateTime();

        List<TxnOrder> paidToday = orderRepo.findAll(paidSpec(store, start, end));
        long revenueFen = 0L;
        long contraYellow = 0L;
        long contraRed = 0L;
        Set<String> todayCustomers = new HashSet<>();
        for (TxnOrder o : paidToday) {
            if (o.getAmount() != null) {
                revenueFen += o.getAmount();
            }
            if ("YELLOW".equals(o.getContraCheck())) {
                contraYellow++;
            } else if ("RED".equals(o.getContraCheck())) {
                contraRed++;
            }
            if (o.getCustomerId() != null && !o.getCustomerId().isBlank()) {
                todayCustomers.add(o.getCustomerId());
            }
        }

        long arrivalCount = arrivalRepo.count(arrivalSpec(store, start, end));

        List<TxnRefund> refundsToday = refundRepo.findAll(refundSpec(store, start, end));
        long refundFen = 0L;
        for (TxnRefund r : refundsToday) {
            if (r.getRefundAmt() != null) {
                refundFen += r.getRefundAmt();
            }
        }

        long newCustomerCount = countNewCustomers(store, todayCustomers, start, end);

        return new DailyMetricsView(
                day.toString(),
                store == null ? "ALL" : store,
                "CNY",
                revenueFen,
                (long) paidToday.size(),
                arrivalCount,
                newCustomerCount,
                (long) refundsToday.size(),
                refundFen,
                contraYellow,
                contraRed,
                (long) refundsToday.size() + contraYellow + contraRed);
    }

    /**
     * 新客：当日已收款客户在「最近 SCAN_LIMIT 笔已收款订单（按创建倒序）」中，其时间最早一笔
     * （窗口内首单）落在报告自然日的客户数。更早成交已超扫描窗口的客户无法被识别为非新客
     * （口径边界，同流失候选窗口注释）；演示/单店规模下窗口足以覆盖全量成交历史。
     */
    private long countNewCustomers(String store, Set<String> todayCustomers,
                                   OffsetDateTime start, OffsetDateTime end) {
        if (todayCustomers.isEmpty()) {
            return 0L;
        }
        List<TxnOrder> recent = store == null
                ? orderRepo.findByStatusOrderByCreatedAtDesc("已收款", PageRequest.of(0, SCAN_LIMIT)).getContent()
                : orderRepo.findByStoreCodeAndStatusOrderByCreatedAtDesc(
                        store, "已收款", PageRequest.of(0, SCAN_LIMIT)).getContent();
        Set<String> firstSeen = new HashSet<>();
        long newCount = 0L;
        for (int i = recent.size() - 1; i >= 0; i--) {
            TxnOrder o = recent.get(i);
            String cid = o.getCustomerId();
            if (cid == null || cid.isBlank() || !todayCustomers.contains(cid) || firstSeen.contains(cid)) {
                continue;
            }
            firstSeen.add(cid);
            OffsetDateTime t = o.getCreatedAt();
            if (t != null && !t.isBefore(start) && t.isBefore(end)) {
                newCount++;
            }
        }
        return newCount;
    }

    private Specification<TxnOrder> paidSpec(String store, OffsetDateTime start, OffsetDateTime end) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("status"), "已收款"));
            if (store != null) {
                ps.add(cb.equal(root.get("storeCode"), store));
            }
            ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            ps.add(cb.lessThan(root.get("createdAt"), end));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Specification<Arrival> arrivalSpec(String store, OffsetDateTime start, OffsetDateTime end) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (store != null) {
                ps.add(cb.equal(root.get("storeCode"), store));
            }
            ps.add(cb.greaterThanOrEqualTo(root.get("arrivedAt"), start));
            ps.add(cb.lessThan(root.get("arrivedAt"), end));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** 已完成退款按 refunded_at 落自然日（退款实际完成时刻口径）；未完成退款不计当日异常。 */
    private Specification<TxnRefund> refundSpec(String store, OffsetDateTime start, OffsetDateTime end) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("status"), "REFUNDED"));
            if (store != null) {
                ps.add(cb.equal(root.get("storeCode"), store));
            }
            ps.add(cb.greaterThanOrEqualTo(root.get("refundedAt"), start));
            ps.add(cb.lessThan(root.get("refundedAt"), end));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /**
     * 单日经营指标视图。金额单位：revenueFen/refundFen 为「分」；异常项 anomalyCount =
     * 已完成退款笔数 + 当日已收款订单 contraCheck YELLOW/RED 笔数。
     */
    public record DailyMetricsView(
            String date, String store, String currency,
            Long revenueFen, Long paidOrderCount, Long arrivalCount, Long newCustomerCount,
            Long refundCount, Long refundFen, Long contraYellowCount, Long contraRedCount,
            Long anomalyCount) {
    }
}
