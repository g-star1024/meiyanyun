package com.meiyun.txn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M1 大屏实时成交流扇出 Job（B49 卡7，NotificationFanoutJob 同构纯新增，零侵入收款主链路）：
 * 每 3s 轮询 order_payment 增量流水，富化（订单项目/门店、客户脱敏名）后经 ScreenStreamRegistry
 * 向全部在线大屏广播 {@code name="order-paid"}。
 *
 * <p>游标：内存 watermark（不持久化），自服务启动时刻起只推新流水，<b>重启不补历史</b>
 * （大屏定位可接受，设计风险③如实标注）。同刻多行防丢：查询窗口自水位回退 2s 重叠 +
 * recentIds 去重环（上限 {@link #RECENT_CAP} 挤出最旧）。无在线连接时仅推进水位、
 * 跳过富化查询（不给 customer 制造空转流量）。fixedDelay 串行不重入，无需加锁。
 */
@Component
public class ScreenOrderFanoutJob {

    private static final Logger log = LoggerFactory.getLogger(ScreenOrderFanoutJob.class);

    /** 同刻行重叠窗口（秒）：水位回退查询，配合 recentIds 去重防丢防重。 */
    private static final long OVERLAP_SECONDS = 2L;
    private static final int RECENT_CAP = 500;

    private final OrderPaymentRepository paymentRepo;
    private final TxnOrderRepository orderRepo;
    private final CustomerDirectoryClient customerClient;
    private final ScreenStreamRegistry registry;

    /** 启动时刻水位：只推服务启动后的新流水（重启不补历史）。 */
    private OffsetDateTime watermark = OffsetDateTime.now();
    /** 已推 paymentId 去重环（插入序，超 cap 挤出最旧）。 */
    private final Set<String> recentIds = new LinkedHashSet<>();

    public ScreenOrderFanoutJob(OrderPaymentRepository paymentRepo,
                                TxnOrderRepository orderRepo,
                                CustomerDirectoryClient customerClient,
                                ScreenStreamRegistry registry) {
        this.paymentRepo = paymentRepo;
        this.orderRepo = orderRepo;
        this.customerClient = customerClient;
        this.registry = registry;
    }

    @Scheduled(fixedDelay = 3000L, initialDelay = 15_000L)
    public void fanout() {
        try {
            List<OrderPayment> fresh = paymentRepo.findByCreatedAtAfterOrderByCreatedAtAsc(
                    watermark.minusSeconds(OVERLAP_SECONDS));
            if (fresh.isEmpty()) {
                return;
            }
            List<OrderPayment> unsent = new ArrayList<>();
            for (OrderPayment p : fresh) {
                if (p.getCreatedAt() != null && p.getCreatedAt().isAfter(watermark)) {
                    watermark = p.getCreatedAt();
                }
                if (p.getPaymentId() != null && !recentIds.contains(p.getPaymentId())) {
                    unsent.add(p);
                }
            }
            if (unsent.isEmpty() || registry.isEmpty()) {
                // 水位已推进；无新行或无在线连接时跳过富化与广播
                return;
            }

            List<String> orderNos = unsent.stream().map(OrderPayment::getOrderNo).distinct().toList();
            Map<String, TxnOrder> orderByNo = new HashMap<>();
            for (TxnOrder o : orderRepo.findByOrderNoIn(orderNos)) {
                orderByNo.put(o.getOrderNo(), o);
            }
            List<String> customerIds = orderByNo.values().stream()
                    .map(TxnOrder::getCustomerId)
                    .filter(c -> c != null && !c.isBlank())
                    .distinct()
                    .toList();
            Map<String, String> names = customerClient.nameMap(customerIds);

            for (OrderPayment p : unsent) {
                try {
                    TxnOrder o = orderByNo.get(p.getOrderNo());
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("paymentId", p.getPaymentId());
                    payload.put("orderNo", p.getOrderNo());
                    payload.put("storeCode", o == null || o.getStoreCode() == null ? "" : o.getStoreCode());
                    payload.put("customerName", o == null ? "—" : names.getOrDefault(o.getCustomerId(), "—"));
                    payload.put("item", o == null || o.getProject() == null ? "" : o.getProject());
                    payload.put("amountFen", p.getPostedAmount() == null ? 0L : p.getPostedAmount());
                    payload.put("payMethod", p.getPayMethod() == null ? "" : p.getPayMethod());
                    payload.put("paidAt", p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
                    registry.broadcast(payload);
                    markSent(p.getPaymentId());
                } catch (Exception ex) {
                    log.warn("大屏成交流单条扇出失败（跳过不影响后续）paymentId={}: {}",
                            p.getPaymentId(), ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("大屏成交流扇出轮次异常（下轮自愈）: {}", e.getMessage());
        }
    }

    private void markSent(String paymentId) {
        recentIds.add(paymentId);
        if (recentIds.size() > RECENT_CAP) {
            Iterator<String> it = recentIds.iterator();
            it.next();
            it.remove();
        }
    }
}
