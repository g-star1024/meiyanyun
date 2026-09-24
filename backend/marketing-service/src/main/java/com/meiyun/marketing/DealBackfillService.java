package com.meiyun.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 成交回写引擎（P5-B92，DESIGN-P5-B92 §3.3，AutoGrantService 范式）。
 *
 * <p>定时任务 {@link DealBackfillJob} 驱动，每节拍两段、分段游标（deal_backfill_state 单行）：
 * <ul>
 *   <li><b>paid 段</b>：窗口 (last_paid_at, now]（日粒度闭区间拉取、游标按瞬时推进，重叠靠幂等吞）
 *       拉 txn 已收款订单 → 过滤 sourceType∈{LIVE_SESSION, SHORT_VIDEO} → 逐单 INSERT
 *       mkt_deal_attribution（order_no 冲突＝已处理静默跳过）→ 成功插入才正向增量
 *       场次/视频 deal_count+1、deal_amount+=amount；来源行不存在跳过计数增量计入 skipped
 *      （归属行仍落，防重放丢单）；</li>
 *   <li><b>refund 段</b>：窗口 (last_refunded_at, now] 拉已退款流水 → 命中 attribution.status=PAID
 *       → 置 REFUNDED＋refunded_at＋负向冲销（deal_count-1、deal_amount-=amount，floor 0 保护）；
 *       未命中跳过（无来源单/已冲销单自然免疫）。</li>
 * </ul>
 *
 * <p>事务边界：两段各自独立事务（Job 经代理分别调用），段成功才推进对应游标；
 * txn 域不可用（TxnServiceUnavailableException）返回 error 且不推进游标，下轮自愈，
 * 与自动发赠金「基础设施故障不毒化游标」同口径。两段互不影响（paid 故障不阻塞 refund）。
 */
@Service
public class DealBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DealBackfillService.class);

    private final TxnInternalClient txnClient;
    private final MktDealAttributionRepository attrRepo;
    private final DealBackfillStateRepository stateRepo;
    private final LiveSessionRepository sessionRepo;
    private final ShortVideoRepository videoRepo;

    public DealBackfillService(TxnInternalClient txnClient, MktDealAttributionRepository attrRepo,
                               DealBackfillStateRepository stateRepo,
                               LiveSessionRepository sessionRepo, ShortVideoRepository videoRepo) {
        this.txnClient = txnClient;
        this.attrRepo = attrRepo;
        this.stateRepo = stateRepo;
        this.sessionRepo = sessionRepo;
        this.videoRepo = videoRepo;
    }

    /** paid 段结果。error 非空＝txn 域故障、游标未推进（下轮重试）。 */
    public record PaidResult(int fetched, int inserted, int skipped, String error) {}

    /** refund 段结果。error 非空＝txn 域故障、游标未推进（下轮重试）。 */
    public record RefundResult(int fetched, int refunded, String error) {}

    /** paid 段：已收款订单 → 归属登记＋来源正向增量；成功才推进 last_paid_at。 */
    @Transactional
    public PaidResult paidSegment() {
        OffsetDateTime now = OffsetDateTime.now();
        DealBackfillState state = state();
        OffsetDateTime from = state.getLastPaidAt() == null
                ? OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                : state.getLastPaidAt();
        List<TxnInternalClient.PaidOrder> paid;
        try {
            paid = txnClient.fetchPaidOrders(from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    now.format(DateTimeFormatter.ISO_LOCAL_DATE));
        } catch (TxnServiceUnavailableException ex) {
            return new PaidResult(0, 0, 0, ex.getMessage());
        }
        int inserted = 0;
        int skipped = 0;
        for (TxnInternalClient.PaidOrder o : paid) {
            if (!isSourced(o.sourceType())) continue;
            if (o.orderNo() == null || o.orderNo().isBlank()) continue;
            if (o.sourceId() == null || o.sourceId().isBlank()) continue;
            if (o.amount() == null || o.amount() <= 0) continue;
            // 订单级幂等锚：一笔订单至多归属一次（邻轮窗口重叠/重跑静默吞掉）
            if (attrRepo.findByOrderNo(o.orderNo()).isPresent()) continue;
            MktDealAttribution a = new MktDealAttribution();
            a.setOrderNo(o.orderNo());
            a.setSourceType(o.sourceType());
            a.setSourceId(o.sourceId());
            a.setAmount(o.amount());
            a.setStatus("PAID");
            a.setPaidAt(o.createdAt() == null ? now : o.createdAt());
            attrRepo.save(a);
            if (applyDelta(o.sourceType(), o.sourceId(), 1, o.amount())) {
                inserted++;
            } else {
                // 来源行不存在（极端脏数据）：归属行保留防重放，计数增量放弃
                skipped++;
                log.warn("成交回写跳过计数增量（来源不存在）：orderNo={} {}={}",
                        o.orderNo(), o.sourceType(), o.sourceId());
            }
        }
        state.setLastPaidAt(now);
        stateRepo.save(state);
        return new PaidResult(paid.size(), inserted, skipped, null);
    }

    /** refund 段：已退款流水 → 命中 PAID 归属置 REFUNDED＋负向冲销；成功才推进 last_refunded_at。 */
    @Transactional
    public RefundResult refundSegment() {
        OffsetDateTime now = OffsetDateTime.now();
        DealBackfillState state = state();
        OffsetDateTime from = state.getLastRefundedAt() == null
                ? OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                : state.getLastRefundedAt();
        List<TxnInternalClient.RefundedOrder> refunds;
        try {
            refunds = txnClient.fetchRefundedOrders(from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    now.format(DateTimeFormatter.ISO_LOCAL_DATE));
        } catch (TxnServiceUnavailableException ex) {
            return new RefundResult(0, 0, ex.getMessage());
        }
        int refunded = 0;
        for (TxnInternalClient.RefundedOrder r : refunds) {
            if (r.orderNo() == null || r.orderNo().isBlank()) continue;
            MktDealAttribution a = attrRepo.findByOrderNo(r.orderNo()).orElse(null);
            if (a == null || !"PAID".equals(a.getStatus())) continue;
            a.setStatus("REFUNDED");
            a.setRefundedAt(now);
            attrRepo.save(a);
            applyDelta(a.getSourceType(), a.getSourceId(), -1, -a.getAmount());
            refunded++;
        }
        state.setLastRefundedAt(now);
        stateRepo.save(state);
        return new RefundResult(refunds.size(), refunded, null);
    }

    private boolean isSourced(String sourceType) {
        return "LIVE_SESSION".equals(sourceType) || "SHORT_VIDEO".equals(sourceType);
    }

    /** 场次/视频成交计数增减（floor 0 保护，退款负向冲销不出负数）。来源行不存在返回 false。 */
    private boolean applyDelta(String sourceType, String sourceId, int dCount, long dAmount) {
        if ("LIVE_SESSION".equals(sourceType)) {
            LiveSession s = sessionRepo.findById(sourceId).orElse(null);
            if (s == null) return false;
            s.setDealCount(Math.max(0, s.getDealCount() + dCount));
            s.setDealAmount(Math.max(0L, s.getDealAmount() + dAmount));
            sessionRepo.save(s);
            return true;
        }
        ShortVideo v = videoRepo.findById(sourceId).orElse(null);
        if (v == null) return false;
        v.setDealCount(Math.max(0, v.getDealCount() + dCount));
        v.setDealAmount(Math.max(0L, v.getDealAmount() + dAmount));
        videoRepo.save(v);
        return true;
    }

    private DealBackfillState state() {
        return stateRepo.findById((short) 1).orElseGet(() -> {
            DealBackfillState s = new DealBackfillState();
            s.setId((short) 1);
            return s;
        });
    }
}
