package com.meiyun.customer;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户月消费事实聚合引擎（域①-B62 卡1）。
 *
 * <p>定时任务 {@link MonthlySpendJob} 与手动端点共同驱动：按「北京时区已闭合自然月」逐月从交易域内部端点
 * 拉取全部门店的已收款（剔除 CARD_SALE 储值购卡）与已退款（退卡不入窗），按客户汇总分金额后
 * upsert 覆盖 customer_monthly_spend（net_fen = paid_fen - refund_fen，可为负），作为后续升降级判定的唯一事实真源。
 *
 * <p>游标 monthly_spend_state 单行（state_id=1）：last_closed_month 只推进到北京当前月的上一闭合月，
 * 在跑月绝不聚合；NULL=从未运行，首次从 2025-01 逐月回填。每月独立事务提交，某月拉取/落库失败立即返回 error
 * 且不推进该月游标（已完成月份不回滚），下轮断点续跑。upsert 覆盖重算，天然幂等。
 *
 * <p>服务间调用 txn-service 内部只读端点携带 X-Internal-Token；txn 不可用时降级为「本轮跳过、下轮自愈」，
 * 不阻断客户域主链路（与 {@link AutoPointsService} 同哲学）。
 */
@Service
public class MonthlySpendService {

    private static final Logger log = LoggerFactory.getLogger(MonthlySpendService.class);
    private static final ZoneId BJ = ZoneId.of("Asia/Shanghai");
    private static final YearMonth FIRST_MONTH = YearMonth.of(2025, 1);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final ParameterizedTypeReference<List<AutoPointsService.PaidOrderView>> PAID_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<AutoPointsService.RefundedOrderView>> REFUND_TYPE =
            new ParameterizedTypeReference<>() {};

    private final CustomerRepository customerRepo;
    private final MonthlySpendWriter writer;
    private final MonthlySpendStateRepository stateRepo;
    private final RestTemplate restTemplate;

    @Value("${txn.service.url:http://localhost:8083}")
    private String txnBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public MonthlySpendService(CustomerRepository customerRepo, MonthlySpendWriter writer,
                               MonthlySpendStateRepository stateRepo, RestTemplate restTemplate) {
        this.customerRepo = customerRepo;
        this.writer = writer;
        this.stateRepo = stateRepo;
        this.restTemplate = restTemplate;
    }

    /** 聚合结果：months=本轮实际聚合的闭合月（yyyy-MM，顺序），error 非空表示某月失败、游标停在上一成功月。 */
    public record AggregateResult(List<String> months, int upsertRows,
                                  long paidOrders, long refundCount, String error) {}

    /**
     * 聚合所有尚未入表的已闭合月。北京当前月的上一月为上界；已追平则零请求直接返回空结果。
     * 逐月拉取→按月独立事务 upsert+推进游标；任一月份拉取失败立即返回 error 且不推进该月。
     */
    public AggregateResult aggregateClosedMonths() {
        YearMonth closed = YearMonth.now(BJ).minusMonths(1);
        MonthlySpendState state = stateRepo.findById(1).orElseGet(() -> {
            MonthlySpendState s = new MonthlySpendState();
            s.setStateId(1);
            return s;
        });
        YearMonth start = state.getLastClosedMonth() == null
                ? FIRST_MONTH
                : YearMonth.parse(state.getLastClosedMonth()).plusMonths(1);
        if (start.isAfter(closed)) {
            return new AggregateResult(List.of(), 0, 0, 0, null);
        }

        List<String> months = new ArrayList<>();
        int totalRows = 0;
        long totalPaidOrders = 0;
        long totalRefundCount = 0;
        for (YearMonth m = start; !m.isAfter(closed); m = m.plusMonths(1)) {
            String period = m.toString();
            LocalDate from = m.atDay(1);
            LocalDate to = m.atEndOfMonth();

            List<AutoPointsService.PaidOrderView> paid;
            try {
                paid = fetchPaid(from.format(ISO_DATE), to.format(ISO_DATE));
            } catch (Exception ex) {
                String msg = "拉取已收款订单失败（txn 不可用），月 " + period + " 未聚合：" + ex.getMessage();
                log.warn("月消费聚合 {}", msg);
                return new AggregateResult(months, totalRows, totalPaidOrders, totalRefundCount, msg);
            }
            List<AutoPointsService.RefundedOrderView> refunds;
            try {
                refunds = fetchRefunded(from.format(ISO_DATE), to.format(ISO_DATE));
            } catch (Exception ex) {
                String msg = "拉取已退款流水失败（txn 不可用），月 " + period + " 未聚合：" + ex.getMessage();
                log.warn("月消费聚合 {}", msg);
                return new AggregateResult(months, totalRows, totalPaidOrders, totalRefundCount, msg);
            }

            Map<String, Long> paidSum = new HashMap<>();
            Map<String, Long> refundSum = new HashMap<>();
            for (AutoPointsService.PaidOrderView o : paid) {
                if (o.customerId() == null || o.customerId().isBlank()) continue;
                if (o.amount() == null || o.amount() <= 0) continue;
                // 售卡/开卡单（充值）不计消费，与自动积分同口径
                if ("CARD_SALE".equals(o.bizKind())) continue;
                paidSum.merge(o.customerId(), o.amount(), Long::sum);
            }
            for (AutoPointsService.RefundedOrderView r : refunds) {
                if (r.customerId() == null || r.customerId().isBlank()) continue;
                if (r.refundAmt() == null || r.refundAmt() <= 0) continue;
                refundSum.merge(r.customerId(), r.refundAmt(), Long::sum);
            }

            // 只保留主档仍存在的客户；门店码取客户主档快照（公海客户为空）
            Map<String, Customer> known = new LinkedHashMap<>();
            List<String> ids = new ArrayList<>();
            for (String cid : paidSum.keySet()) if (!ids.contains(cid)) ids.add(cid);
            for (String cid : refundSum.keySet()) if (!ids.contains(cid)) ids.add(cid);
            for (Customer c : customerRepo.findAllById(ids)) {
                known.put(c.getCustomerId(), c);
            }

            int rows;
            try {
                rows = writer.upsertMonth(period, paidSum, refundSum, known, state,
                        paid.size(), refunds.size());
            } catch (Exception ex) {
                String msg = "月 " + period + " 事实落库失败，游标未推进：" + ex.getMessage();
                log.error("月消费聚合 {}", msg, ex);
                return new AggregateResult(months, totalRows, totalPaidOrders, totalRefundCount, msg);
            }

            months.add(period);
            totalRows += rows;
            totalPaidOrders += paid.size();
            totalRefundCount += refunds.size();
            log.info("月消费聚合完成：period={} paidOrders={} refundCount={} upsertRows={}",
                    period, paid.size(), refunds.size(), rows);
        }
        return new AggregateResult(months, totalRows, totalPaidOrders, totalRefundCount, null);
    }

    private List<AutoPointsService.PaidOrderView> fetchPaid(String from, String to) {
        String url = txnBaseUrl + "/api/txn/internal/paid-orders?from=" + from + "&to=" + to;
        return exchange(url, PAID_TYPE);
    }

    private List<AutoPointsService.RefundedOrderView> fetchRefunded(String from, String to) {
        String url = txnBaseUrl + "/api/txn/internal/refunded-orders?from=" + from + "&to=" + to;
        return exchange(url, REFUND_TYPE);
    }

    private <T> T exchange(String url, ParameterizedTypeReference<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        ResponseEntity<T> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), type);
        return resp.getBody();
    }
}
