package com.meiyun.finance;

import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 三方对账（B7，DESIGN §9.1）：按日聚合 经营域（txn 订单收款/退款/卡扣划扣/退卡）
 * × 资金域（finance fund_entry 落账净额）× 现金日结（txn「现金交接」双签工单实点现金），
 * 输出各方净额（Long「分」+「元」双金额）、门店明细与差异笔数。
 *
 * <p><b>账账核对</b>（经营域 vs 资金域）：两侧口径逐字对齐——
 * 订单收款 RF-REVENUE/IN、退款 RF-REFUND/OUT、卡扣划扣 RF-DEPOSIT/OUT + RF-REVENUE/IN 成对
 * （净额为 0，仅计笔数）、退卡 RF-REFUND/OUT（实退）+ RF-DEPOSIT/OUT（冲预收）+ RF-REVENUE/IN
 * （违约金，fee&gt;0 才计）。资金域另有两类经营域无对应的分录，单列不混入差异：
 * ① MANUAL 期末成本录入（TK-* 科目，财务域自身动作）；② ADJUST 人工调平（outbox 差异处理产物）。
 *
 * <p><b>账实核对</b>（仅现金）：落账/经营侧 CASHIER 且 channel=cash 的净额 vs 双签工单
 * 「现金交接」当日实点现金合计。微信/支付宝/银行回单本期无自动导入，账实核对仅覆盖现金渠道，
 * 不臆造外部数字（红线⑤诚实降级）。
 *
 * <p><b>边界</b>：取数走系统身份内部端点（txn 不做门店域收敛），本服务按登录人
 * {@link DataScope#canReadStore} 逐行二次收敛；显式指定无权门店统一 404 不泄露存在性。
 * 日界：经营/落账两侧 UTC 半开 [date, date+1d)（与 finance-flows / ledger-diff 同口径），
 * 现金方为 Asia/Shanghai 自然日（双签完成时刻 signed_at2，与 M2 交接班页面一致）。
 */
@Service
public class TripartiteReconcileService {

    private static final Logger log = LoggerFactory.getLogger(TripartiteReconcileService.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final FinanceAggregationService aggregation;
    private final FundEntryRepository entryRepo;

    public TripartiteReconcileService(FinanceAggregationService aggregation,
                                      FundEntryRepository entryRepo) {
        this.aggregation = aggregation;
        this.entryRepo = entryRepo;
    }

    /**
     * 三方对账主入口。date 为 yyyy-MM-dd（缺省 Asia/Shanghai 今天）；storeCode 可选。
     */
    public Map<String, Object> tripartite(String date, String storeCode) {
        String day = (date == null || date.isBlank())
                ? LocalDate.now(SHANGHAI).toString() : date.trim();
        LocalDate dayDate;
        try {
            dayDate = LocalDate.parse(day);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "日期参数 date 格式非法，需 yyyy-MM-dd（如 2026-09-05）：" + day);
        }
        String sc = (storeCode == null || storeCode.isBlank()) ? null : storeCode.trim();
        if (sc != null && !DataScope.canReadStore(sc)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }

        // ---------- 经营域：txn 四流（UTC 日界，与 finance-flows 端口径一致） ----------
        Map<String, Object> bundle = aggregation.fetchFlows(sc, day, day);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) bundle.getOrDefault("orders", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refunds = (List<Map<String, Object>>) bundle.getOrDefault("refunds", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> writeoffs = (List<Map<String, Object>>) bundle.getOrDefault("writeoffs", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cardCancels = (List<Map<String, Object>>) bundle.getOrDefault("cardCancels", List.of());

        Map<String, String> orderChannel = new TreeMap<>();
        for (Map<String, Object> o : orders) {
            String ch = mapPayMethod(str(o.get("payMethod")));
            if (ch != null) orderChannel.put(str(o.get("orderNo")), ch);
        }

        // 每店累计：[净额, 现金净额, 订单笔数, 退款笔数, 划扣对笔数, 退卡笔数]
        Map<String, long[]> biz = new TreeMap<>();
        for (Map<String, Object> o : orders) {
            String store = str(o.get("storeCode"));
            if (!DataScope.canReadStore(store)) continue;
            long amount = longOf(o.get("amount"));
            if (amount <= 0) continue;
            long[] a = biz.computeIfAbsent(store, k -> new long[6]);
            a[0] += amount;
            a[2]++;
            if ("cash".equals(mapPayMethod(str(o.get("payMethod"))))) a[1] += amount;
        }
        for (Map<String, Object> r : refunds) {
            String store = str(r.get("storeCode"));
            if (!DataScope.canReadStore(store)) continue;
            long refundAmt = longOf(r.get("refundAmt"));
            if (refundAmt <= 0) continue;
            long[] a = biz.computeIfAbsent(store, k -> new long[6]);
            a[0] -= refundAmt;
            a[3]++;
            String ch = resolveRefundChannel(str(r.get("channel")), str(r.get("orderNo")), orderChannel);
            if ("cash".equals(ch)) a[1] -= refundAmt;
        }
        for (Map<String, Object> w : writeoffs) {
            String store = str(w.get("storeCode"));
            if (!DataScope.canReadStore(store)) continue;
            String cardNo = str(w.get("cardNo"));
            long amount = longOf(w.get("amount"));
            if (cardNo == null || cardNo.isBlank() || amount <= 0) continue; // 整单核销无资金动账
            long[] a = biz.computeIfAbsent(store, k -> new long[6]);
            a[4]++;
            // 卡扣划扣：预收转出 -amount 与确认收入 +amount 成对，净额贡献为 0，仅计笔数
        }
        for (Map<String, Object> c : cardCancels) {
            String store = str(c.get("storeCode"));
            if (!DataScope.canReadStore(store)) continue;
            long refundAmt = longOf(c.get("refundAmt"));
            long balance = longOf(c.get("balance"));
            long fee = longOf(c.get("fee"));
            if (refundAmt <= 0 && balance <= 0 && fee <= 0) continue;
            long[] a = biz.computeIfAbsent(store, k -> new long[6]);
            a[5]++;
            if (refundAmt > 0) {
                a[0] -= refundAmt; // RF-REFUND/OUT 实退
                if ("cash".equals(resolveCcChannel(str(c.get("channel"))))) a[1] -= refundAmt;
            }
            if (balance > 0) a[0] -= balance;   // RF-DEPOSIT/OUT 冲预收（ERP 内部结转）
            if (fee > 0) a[0] += fee;           // RF-REVENUE/IN 违约金收入（ERP）
        }

        // ---------- 资金域：fund_entry 当日区间（UTC 半开，与 ledger-diff 同口径） ----------
        OffsetDateTime fromUtc = dayDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime toUtc = dayDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        // 每店累计：[净额, 现金净额, MANUAL 成本净额, 分录笔数]
        Map<String, long[]> posted = new TreeMap<>();
        long adjustCount = 0;
        long adjustNet = 0;
        for (FundEntry e : entryRepo.findByOccurredAtBetweenOrderByOccurredAtAsc(fromUtc, toUtc)) {
            String store = e.getStoreCode();
            if (!DataScope.canReadStore(store)) continue;
            if (sc != null && !sc.equals(store)) continue;
            long amt = e.getAmount() == null ? 0L : e.getAmount();
            long signed = "IN".equals(e.getDirection()) ? amt : -amt;
            if ("ADJUST".equals(e.getBizType())) {
                adjustCount++;
                adjustNet += signed;
                continue;
            }
            long[] p = posted.computeIfAbsent(store, k -> new long[4]);
            p[0] += signed;
            p[3]++;
            if ("CASHIER".equals(e.getSource()) && "cash".equals(e.getChannel())) p[1] += signed;
            if ("MANUAL".equals(e.getSource())) p[2] += signed; // 财务域独有（期末成本录入），经营域无对应
        }

        // ---------- 现金方：双签工单实点现金（Asia/Shanghai 自然日）；不可用诚实降级 ----------
        Map<String, Object> cashBundle = aggregation.fetchCashSettle(day, sc);
        boolean cashAvailable = cashBundle != null;
        // 每店累计：[交接金额, 工单数]
        Map<String, long[]> cash = new TreeMap<>();
        if (cashAvailable) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> details =
                    (List<Map<String, Object>>) cashBundle.getOrDefault("details", List.of());
            for (Map<String, Object> d : details) {
                String store = str(d.get("storeCode"));
                if (!DataScope.canReadStore(store)) continue;
                long[] c = cash.computeIfAbsent(store, k -> new long[2]);
                c[0] += longOf(d.get("totalAmount"));
                c[1] += longOf(d.get("ticketCount"));
            }
        }

        // ---------- 门店并集 + 店名解析 + 汇总 ----------
        Set<String> stores = new TreeSet<>();
        stores.addAll(biz.keySet());
        stores.addAll(posted.keySet());
        stores.addAll(cash.keySet());
        Map<String, String> storeNames = aggregation.resolveStoreNames(new ArrayList<>(stores));

        List<Map<String, Object>> rows = new ArrayList<>();
        long bizNetTotal = 0, bizCashTotal = 0, postedNetTotal = 0, postedCashTotal = 0;
        long manualTotal = 0, cashHandoverTotal = 0, ticketTotal = 0;
        long orderCount = 0, refundCount = 0, woPairCount = 0, cancelCount = 0, entryCount = 0;
        int diffStoreCount = 0;
        for (String store : stores) {
            long[] b = biz.getOrDefault(store, new long[6]);
            long[] p = posted.getOrDefault(store, new long[4]);
            long[] c = cash.getOrDefault(store, new long[2]);
            long netDiff = p[0] - b[0];
            long unexplained = netDiff - p[2]; // 扣除财务域独有 MANUAL 成本后的账账差异
            long cashDiff = cashAvailable ? p[1] - c[0] : 0L;
            boolean storeMatched = unexplained == 0 && (!cashAvailable || cashDiff == 0);
            if (!storeMatched) diffStoreCount++;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("storeCode", store);
            row.put("storeName", storeNames.getOrDefault(store, store));
            row.put("bizNetFen", b[0]);
            row.put("bizNetYuan", yuan(b[0]));
            row.put("postedNetFen", p[0]);
            row.put("postedNetYuan", yuan(p[0]));
            row.put("netDiffFen", netDiff);
            row.put("netDiffYuan", yuan(netDiff));
            row.put("manualCostFen", p[2]);
            row.put("unexplainedDiffFen", unexplained);
            row.put("bizCashNetFen", b[1]);
            row.put("postedCashNetFen", p[1]);
            row.put("cashHandoverFen", cashAvailable ? c[0] : null);
            row.put("cashTicketCount", c[1]);
            row.put("cashDiffFen", cashAvailable ? cashDiff : null);
            row.put("orderCount", b[2]);
            row.put("refundCount", b[3]);
            row.put("writeoffPairCount", b[4]);
            row.put("cardCancelCount", b[5]);
            row.put("postedEntryCount", p[3]);
            row.put("matched", storeMatched);
            rows.add(row);

            bizNetTotal += b[0];
            bizCashTotal += b[1];
            postedNetTotal += p[0];
            postedCashTotal += p[1];
            manualTotal += p[2];
            cashHandoverTotal += c[0];
            ticketTotal += c[1];
            orderCount += b[2];
            refundCount += b[3];
            woPairCount += b[4];
            cancelCount += b[5];
            entryCount += p[3];
        }

        long netDiffTotal = postedNetTotal - bizNetTotal;
        long unexplainedTotal = netDiffTotal - manualTotal;
        long cashDiffTotal = postedCashTotal - cashHandoverTotal;
        boolean matched = unexplainedTotal == 0 && (!cashAvailable || cashDiffTotal == 0);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("date", dayDate.toString());
        r.put("store", sc == null ? "ALL" : sc);
        r.put("currency", "CNY");
        r.put("unit", "分");
        r.put("matched", matched);
        r.put("diffStoreCount", diffStoreCount);
        r.put("bizNetFen", bizNetTotal);
        r.put("bizNetYuan", yuan(bizNetTotal));
        r.put("postedNetFen", postedNetTotal);
        r.put("postedNetYuan", yuan(postedNetTotal));
        r.put("netDiffFen", netDiffTotal);
        r.put("netDiffYuan", yuan(netDiffTotal));
        r.put("postedManualCostFen", manualTotal);
        r.put("unexplainedDiffFen", unexplainedTotal);
        r.put("bizCashNetFen", bizCashTotal);
        r.put("postedCashNetFen", postedCashTotal);
        r.put("cashAvailable", cashAvailable);
        r.put("cashHandoverFen", cashAvailable ? cashHandoverTotal : null);
        r.put("cashHandoverYuan", cashAvailable ? yuan(cashHandoverTotal) : null);
        r.put("cashTicketCount", cashAvailable ? ticketTotal : null);
        r.put("cashDiffFen", cashAvailable ? cashDiffTotal : null);
        r.put("cashDiffYuan", cashAvailable ? yuan(cashDiffTotal) : null);
        r.put("adjustCount", adjustCount);
        r.put("adjustNetFen", adjustNet);
        r.put("flowCounts", Map.of(
                "orders", orderCount, "refunds", refundCount,
                "writeoffPairs", woPairCount, "cardCancels", cancelCount,
                "postedEntries", entryCount));
        r.put("stores", rows);
        r.put("message", buildMessage(matched, cashAvailable, unexplainedTotal, cashDiffTotal,
                manualTotal, diffStoreCount));
        log.info("tripartite {} store={} matched={} 账账差异(扣成本)={} 分 现金差异={} 分（现金方可用={}，差异门店 {}）",
                dayDate, sc == null ? "ALL" : sc, matched, unexplainedTotal,
                cashAvailable ? cashDiffTotal : null, cashAvailable, diffStoreCount);
        return r;
    }

    private String buildMessage(boolean matched, boolean cashAvailable, long unexplained,
                                long cashDiff, long manualCost, int diffStores) {
        if (!cashAvailable) {
            return "现金日方暂不可用（交易域现金日结拉取失败），本次仅出账账核对结果，不做账实结论；"
                    + "微信/支付宝/银行回单本期未接入，账实核对恢复后仅覆盖现金渠道";
        }
        if (matched) {
            return "三方对账通过：经营域与落账净额一致（账账差异 0 分），现金账实相符（差异 0 分）；"
                    + "期末成本录入 " + manualCost + " 分属财务域独有动作，已单列不参与账账比对";
        }
        return "三方对账存在差异（" + diffStores + " 家门店）：账账差异（扣除期末成本录入 "
                + manualCost + " 分后）" + unexplained + " 分，现金账实差异 " + cashDiff
                + " 分；请按门店明细排查，差异在 outbox 挂 DIFF 后人工调平（ADJUST）。"
                + "微信/支付宝/银行回单本期未接入，账实核对仅覆盖现金渠道";
    }

    // ==================== 渠道口径（与 FinanceAggregationService 同规则） ====================

    /** 订单收款渠道码直通；未知/空保守 null。 */
    private static String mapPayMethod(String payMethod) {
        if (payMethod == null) return null;
        return switch (payMethod) {
            case "cash", "card", "wxpay", "alipay", "balance" -> payMethod;
            default -> null;
        };
    }

    /** 退款渠道：CASH→cash、TRANSFER→transfer、ORIGINAL→反查原单主渠道（查不到 null）。 */
    private static String resolveRefundChannel(String channel, String orderNo, Map<String, String> orderChannel) {
        if (channel == null) return null;
        return switch (channel) {
            case "CASH" -> "cash";
            case "TRANSFER" -> "transfer";
            case "ORIGINAL" -> orderNo == null ? null : orderChannel.get(orderNo);
            default -> null;
        };
    }

    /** 退卡渠道：CASH→cash、TRANSFER→transfer；退卡无原单号，ORIGINAL 保守 null。 */
    private static String resolveCcChannel(String channel) {
        if (channel == null) return null;
        return switch (channel) {
            case "CASH" -> "cash";
            case "TRANSFER" -> "transfer";
            default -> null;
        };
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long longOf(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 分 → 元（两位小数）。 */
    private static double yuan(long fen) {
        return Math.round(fen / 100.0 * 100.0) / 100.0;
    }
}
