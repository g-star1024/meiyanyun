package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户域积分引擎数据源（服务间内部端点，非业务页面）。
 *
 * <p>本端点与 B24 的 {@code InternalFinanceController} 相互独立、互不依赖——仅新增只读投影，
 * 不修改任何 B24 文件。面向客户域「消费自动积分」定时任务，以系统身份（X-Internal-Token）开放
 * 已收款订单 / 已退款流水两类只读投影，供 customer-service 计算积分发放与退款回退。
 *
 * <p>权限复用 {@code internal:finance-flow}（内部身份已具备，finance-service 同源调用），
 * 普通登录人无此权限 → 403。不做 DataScope 门店域收敛（由 customer-service 按客户归属二次处理）。
 *
 * <p>过滤：storeCode / from / to（yyyy-MM-dd，按 created_at 闭区间）均可选。
 */
@RestController
@RequestMapping("/api/txn/internal")
public class InternalOrderController {

    private final TxnOrderRepository orderRepo;
    private final TxnRefundRepository refundRepo;

    public InternalOrderController(TxnOrderRepository orderRepo, TxnRefundRepository refundRepo) {
        this.orderRepo = orderRepo;
        this.refundRepo = refundRepo;
    }

    /**
     * 已收款订单只读投影：GET /api/txn/internal/paid-orders?storeCode=&from=&to=。
     * 返回 status='已收款' 订单的精简字段（orderNo/customerId/storeCode/amount/status/bizKind/createdAt），
     * 金额单位 Long「分」。供积分引擎按已收款订单自动发放积分。
     */
    @GetMapping("/paid-orders")
    @RequirePerm("internal:finance-flow")
    public List<PaidOrderView> paidOrders(
            @RequestParam(value = "storeCode", required = false) String storeCode,
            @RequestParam(value = "from", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to) {
        return orderRepo.findAll(paidSpec(storeCode, from, to)).stream()
                .map(o -> new PaidOrderView(o.getOrderNo(), o.getCustomerId(), o.getStoreCode(),
                        o.getAmount(), o.getStatus(), o.getBizKind(), o.getCreatedAt()))
                .toList();
    }

    /**
     * 已退款流水只读投影：GET /api/txn/internal/refunded-orders?storeCode=&from=&to=。
     * 返回 status='REFUNDED' 流水的精简字段（txnNo/orderNo/customerId/storeCode/refundAmt/createdAt），
     * 金额单位 Long「分」。供积分引擎对退款订单回退已发积分。
     */
    @GetMapping("/refunded-orders")
    @RequirePerm("internal:finance-flow")
    public List<RefundedOrderView> refundedOrders(
            @RequestParam(value = "storeCode", required = false) String storeCode,
            @RequestParam(value = "from", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to) {
        List<TxnRefund> refunds = refundRepo.findAll(refundSpec(storeCode, from, to));
        // txn_refund.customer 仅冗余客户姓名（非客户域 M 编号），客户 ID 必须从原单 txn_order.customer_id 解析，
        // 否则 customer-service 按姓名 findById 必然 NotFound（运行态冒烟以真实数据核实）。批量预载避免 N+1。
        List<String> orderNos = refunds.stream().map(TxnRefund::getOrderNo)
                .filter(no -> no != null && !no.isBlank()).distinct().toList();
        java.util.Map<String, String> orderCustomer = orderNos.isEmpty()
                ? java.util.Map.of()
                : orderRepo.findAllById(orderNos).stream()
                        .collect(java.util.stream.Collectors.toMap(TxnOrder::getOrderNo,
                                o -> o.getCustomerId() == null ? "" : o.getCustomerId(), (a, b) -> a));
        return refunds.stream()
                .map(r -> new RefundedOrderView(r.getTxnNo(), r.getOrderNo(),
                        r.getOrderNo() == null ? null : orderCustomer.get(r.getOrderNo()),
                        r.getStoreCode(), r.getRefundAmt(), r.getCreatedAt()))
                .toList();
    }

    /** 已收款订单规格：status='已收款' + 可选门店/时间区间（UTC 日界）。 */
    private Specification<TxnOrder> paidSpec(String storeCode, LocalDate from, LocalDate to) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("status"), "已收款"));
            if (storeCode != null && !storeCode.isBlank()) ps.add(cb.equal(root.get("storeCode"), storeCode));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                    from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"),
                    to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** 已退款流水规格：status='REFUNDED' + 可选门店/时间区间（UTC 日界）。 */
    private Specification<TxnRefund> refundSpec(String storeCode, LocalDate from, LocalDate to) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("status"), "REFUNDED"));
            if (storeCode != null && !storeCode.isBlank()) ps.add(cb.equal(root.get("storeCode"), storeCode));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                    from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"),
                    to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** 已收款订单精简视图。 */
    public record PaidOrderView(String orderNo, String customerId, String storeCode,
                               Long amount, String status, String bizKind, OffsetDateTime createdAt) {}

    /** 已退款流水精简视图。 */
    public record RefundedOrderView(String txnNo, String orderNo, String customerId,
                                   String storeCode, Long refundAmt, OffsetDateTime createdAt) {}
}
