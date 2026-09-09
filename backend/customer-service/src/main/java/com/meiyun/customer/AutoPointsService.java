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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 消费自动积分引擎（域①-258）。
 *
 * <p>定时任务 {@link AutoPointsJob} 驱动：拉取窗口内「已收款订单」自动发放积分、「已退款流水」回退积分。
 * 积分计算：yuan = amount(分) / 100；points = floor(yuan × earn_rate × points_multiplier)，
 * earn_rate / points_multiplier 取自 point_rule 单行（兜底各 1）。
 * 幂等：发放 clientToken = "AUTOPOINTS:{orderNo}"、回退 clientToken = "AUTOPOINTS_REFUND:{orderNo}"，
 * 复用 {@link CustomerService#adjustPoints} 既有 clientToken 唯一判重，重放不重复加减分、不重复落审计。
 *
 * <p>服务间调用 txn-service 内部只读端点（/api/txn/internal/paid-orders、/refunded-orders），
 * 携带 X-Internal-Token；txn 不可用时降级为「本轮跳过、下轮自愈」，不阻断客户域主链路（与 RefNameResolver 同哲学）。
 */
@Service
public class AutoPointsService {

    private static final Logger log = LoggerFactory.getLogger(AutoPointsService.class);
    private static final String ACTOR = "SYSTEM";
    private static final ParameterizedTypeReference<List<PaidOrderView>> PAID_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<RefundedOrderView>> REFUND_TYPE =
            new ParameterizedTypeReference<>() {};

    private final CustomerService customerService;
    private final PointRuleRepository pointRuleRepo;
    private final LevelRuleConfigRepository levelRuleRepo;
    private final AutoPointsStateRepository stateRepo;
    private final RestTemplate restTemplate;

    @Value("${txn.service.url:http://localhost:8083}")
    private String txnBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public AutoPointsService(CustomerService customerService, PointRuleRepository pointRuleRepo,
                            LevelRuleConfigRepository levelRuleRepo,
                            AutoPointsStateRepository stateRepo, RestTemplate restTemplate) {
        this.customerService = customerService;
        this.pointRuleRepo = pointRuleRepo;
        this.levelRuleRepo = levelRuleRepo;
        this.stateRepo = stateRepo;
        this.restTemplate = restTemplate;
    }

    /** 单次扫描结果。 */
    public record ScanResult(long scanned, long awardedOrders, long refundedOrders,
                             long awardedPoints, long refundedPoints, String error) {}

    /** 已收款订单视图（与 txn InternalOrderController.PaidOrderView 字段对齐）。 */
    public record PaidOrderView(String orderNo, String customerId, String storeCode,
                               Long amount, String status, String bizKind, OffsetDateTime createdAt) {}
    /** 已退款流水视图。 */
    public record RefundedOrderView(String txnNo, String orderNo, String customerId,
                                   String storeCode, Long refundAmt, OffsetDateTime createdAt) {}

    /**
     * 执行一次积分对账：窗口 = (last_run_at, now]。扫描已收款订单发分、已退款流水回退，
     * 成功后把 last_run_at 推进到当前窗口上界。txn 不可用时返回 error 且不推进游标（下轮重试）。
     */
    @Transactional
    public ScanResult scan() {
        OffsetDateTime now = OffsetDateTime.now();
        AutoPointsState state = stateRepo.findById(1).orElseGet(() -> {
            AutoPointsState s = new AutoPointsState();
            s.setStateId(1);
            return s;
        });
        // 首次（NULL）→ 远早于系统的下界做全量回填；否则从 last_run_at 续跑
        OffsetDateTime from = state.getLastRunAt() == null
                ? OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                : state.getLastRunAt();
        String fromStr = from.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String toStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE);

        long scanned = 0, awardedOrders = 0, refundedOrders = 0, awardedPoints = 0, refundedPoints = 0;
        try {
            // 1) 已收款订单 → 发分
            List<PaidOrderView> paid;
            try {
                paid = fetchPaid(fromStr, toStr);
            } catch (Exception ex) {
                return new ScanResult(0, 0, 0, 0, 0, "拉取已收款订单失败（txn 不可用）：" + ex.getMessage());
            }
            for (PaidOrderView o : paid) {
                scanned++;
                if (o.customerId() == null || o.customerId().isBlank()) continue;
                if (o.amount() == null || o.amount() <= 0) continue;
                // 售卡/开卡单（充值）不计消费积分，防充值循环积分；售卡履约走会员卡逐次划扣
                if ("CARD_SALE".equals(o.bizKind())) continue;
                long pts = computePoints(o.amount());
                if (pts <= 0) continue;
                try {
                    customerService.adjustPoints(o.customerId(), pts,
                            "消费自动积分（订单 " + o.orderNo() + "）", "AUTOPOINTS:" + o.orderNo());
                    awardedOrders++;
                    awardedPoints += pts;
                } catch (CustomerService.NotFound | CustomerService.Conflict ex) {
                    // 客户不存在（已删）/ 幂等重放（已发过）→ 计入已发不计错
                    if (ex instanceof CustomerService.Conflict) {
                        awardedOrders++;
                        awardedPoints += pts;
                    }
                } catch (CustomerService.Unprocessable ex) {
                    log.warn("自动积分跳过（余额不足不应发生于发放）：orderNo={} customer={}", o.orderNo(), o.customerId());
                }
            }

            // 2) 已退款流水 → 回退（按原订单号回退等额积分，幂等键区分）
            List<RefundedOrderView> refunds;
            try {
                refunds = fetchRefunded(fromStr, toStr);
            } catch (Exception ex) {
                return new ScanResult(scanned, awardedOrders, 0, awardedPoints, 0,
                        "拉取已退款流水失败（txn 不可用）：" + ex.getMessage());
            }
            for (RefundedOrderView r : refunds) {
                scanned++;
                if (r.customerId() == null || r.customerId().isBlank()) continue;
                long base = r.refundAmt() == null ? 0 : r.refundAmt();
                if (base <= 0) continue;
                // 回退积分按退款金额折算（与发分同口径），取负；不足扣时 Ignore（不可因退款致负分）
                long pts = -computePoints(base);
                if (pts == 0) continue;
                try {
                    customerService.adjustPoints(r.customerId(), pts,
                            "退款回退积分（退款单 " + r.txnNo() + " / 订单 " + r.orderNo() + "）",
                            "AUTOPOINTS_REFUND:" + r.orderNo());
                    refundedOrders++;
                    refundedPoints += Math.abs(pts);
                } catch (CustomerService.Conflict ex) {
                    refundedOrders++;
                    refundedPoints += Math.abs(pts);
                } catch (CustomerService.NotFound ex) {
                    // 客户不存在 → 跳过
                } catch (CustomerService.Unprocessable ex) {
                    // 余额不足（退款回退导致负分）→ 不强制扣负，记录日志后跳过，留人工调平
                    log.warn("退款回退积分跳过（积分不足，留人工调平）：txnNo={} customer={}", r.txnNo(), r.customerId());
                }
            }

            // 3) 推进游标
            state.setLastRunAt(now);
            state.setLastScanned(scanned);
            state.setLastAwarded(awardedPoints);
            state.setLastRefundedPoints(refundedPoints);
            stateRepo.save(state);
        } catch (Exception ex) {
            log.error("自动积分扫描异常", ex);
            return new ScanResult(scanned, awardedOrders, refundedOrders, awardedPoints, refundedPoints,
                    "扫描异常：" + ex.getMessage());
        }
        return new ScanResult(scanned, awardedOrders, refundedOrders, awardedPoints, refundedPoints, null);
    }

    /** 金额（分）→ 积分：yuan × earnRate × multiplier，向下取整（积分不可为负/小数）。 */
    long computePoints(long amountFen) {
        BigDecimal yuan = BigDecimal.valueOf(amountFen).divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
        PointRule rule = pointRuleRepo.findById(1).orElse(null);
        BigDecimal earn = rule != null && rule.getEarnRate() != null ? rule.getEarnRate() : BigDecimal.ONE;
        // 全局消费积分倍率取自 LevelRuleConfig（pointsMultiplier，事件流接入后生效；缺省 1）
        LevelRuleConfig lrc = levelRuleRepo.findById(1).orElse(null);
        BigDecimal mult = lrc != null && lrc.getPointsMultiplier() != null ? lrc.getPointsMultiplier() : BigDecimal.ONE;
        return yuan.multiply(earn).multiply(mult).setScale(0, java.math.RoundingMode.FLOOR).longValue();
    }

    private List<PaidOrderView> fetchPaid(String from, String to) {
        String url = txnBaseUrl + "/api/txn/internal/paid-orders?from=" + from + "&to=" + to;
        return exchange(url, PAID_TYPE);
    }

    private List<RefundedOrderView> fetchRefunded(String from, String to) {
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
