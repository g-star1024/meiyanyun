package com.meiyun.finance;

import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 非现金渠道账实勾兑（B12 域一，DESIGN §3.4）：按月 × 渠道 × 门店，
 * 「系统账」（fund_entry 非现金渠道收银收款分录）× 「渠道账单」（pay_channel_bill CSV 导入）
 * 以订单号为勾兑键逐单比对，输出双方合计/笔数、已勾兑、漏单（系统有账单无）、
 * 多单/未入账（账单有系统无）、金额不符清单与手续费行汇总（Long「分」+「元」双金额）。
 *
 * <p><b>系统侧口径</b>（与 FinanceEventPublisher 落账逐字对齐）：
 * direction=IN、source=CASHIER、subject=RF-REVENUE、channel∈{wxpay,alipay,transfer}，
 * 订单号从 idem_key「ORDER-PAID:」前缀解析（业务订单号）。余额消费（RF-DEPOSIT/OUT/balance）
 * 被 IN 过滤天然排除；退款落账为 OUT 分录（idem_key=REFUND-PAID:退款单号），<b>不参与
 * 本勾兑</b>——DESIGN 字面系统侧仅 IN 收款分录，账单 REFUND 行在账单侧按状态单列提示。
 *
 * <p><b>账单侧口径</b>：SUCCESS 计入成功金额/笔数；REFUND 计退款金额/笔数；FAILED 仅笔数
 * 不计金额；order_no 空行（手续费行）单列汇总。勾兑键 = 渠道商户单号（与系统订单号一致）。
 *
 * <p><b>比对规则</b>：同一订单号两边都有且系统收款额 = 账单成功额 → 已勾兑；
 * 系统有账单无 → 漏单（客户已付款、渠道结算单缺失，重点排查）；
 * 账单有系统无 → 多单/未入账（系统未记该笔收款，含退款单在系统侧无对应收款的情况）；
 * 两边都有但金额不一致 → 金额不符。
 *
 * <p><b>资金红线</b>：勾兑只对账、只出差异清单，绝不据账单补造实付渠道分录；
 * 不臆造外部数字——账单未导入时诚实降级提示，不做账实相符结论。
 *
 * <p><b>边界</b>：月区间 UTC 半开 [month-01 00:00Z, next-month-01 00:00Z)，
 * 与 finance 既有月报/日结端口径一致（账单 bill_time 存 +8:00 绝对时刻，跨月边界自动归位）；
 * 按登录人 {@link DataScope#canReadStore} 逐行收敛，显式指定无权门店统一 404。
 */
@Service
public class ChannelReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ChannelReconcileService.class);

    /** 参与勾兑的非现金渠道（cash/balance/card 不走渠道结算单，不在本范围）。 */
    static final Set<String> CHANNELS = Set.of("wxpay", "alipay", "transfer");
    /** 系统收款分录幂等键前缀（FinanceEventPublisher ORDER_PAID），其后即业务订单号。 */
    private static final String ORDER_PAID_PREFIX = "ORDER-PAID:";

    private final FundEntryRepository entryRepo;
    private final PayChannelBillRepository billRepo;
    private final FinanceAggregationService aggregation;

    public ChannelReconcileService(FundEntryRepository entryRepo,
                                   PayChannelBillRepository billRepo,
                                   FinanceAggregationService aggregation) {
        this.entryRepo = entryRepo;
        this.billRepo = billRepo;
        this.aggregation = aggregation;
    }

    /**
     * 月勾兑主入口。
     *
     * @param month     yyyy-MM-01（月份契约，任意日号归一到月初）
     * @param channel   渠道码 wxpay/alipay/transfer（必传，渠道结算单按渠道分别导出）
     * @param storeCode 可选门店；不传 = 登录人数据域内全部门店
     */
    public Map<String, Object> reconcile(String month, String channel, String storeCode) {
        LocalDate monthFirst;
        try {
            monthFirst = LocalDate.parse(month).withDayOfMonth(1);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "月份参数 month 格式非法，需 yyyy-MM-01（如 2026-09-01）：" + month);
        }
        if (channel == null || !CHANNELS.contains(channel.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "渠道码 channel 必传且仅支持 wxpay/alipay/transfer，当前：" + channel);
        }
        channel = channel.trim();
        String sc = (storeCode == null || storeCode.isBlank()) ? null : storeCode.trim();
        if (sc != null && !DataScope.canReadStore(sc)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }

        OffsetDateTime fromUtc = monthFirst.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime toUtc = monthFirst.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

        // ---------- 系统侧：fund_entry 非现金渠道收银收款（IN/RF-REVENUE/CASHIER） ----------
        // key = 门店|订单号；value = [收款额(分), 笔数]（同订单幂等，正常 1 笔）
        Map<String, long[]> sysOrders = new TreeMap<>();
        // 每店累计：[收款额, 笔数]
        Map<String, long[]> sysByStore = new TreeMap<>();
        for (FundEntry e : entryRepo.findByOccurredAtBetweenOrderByOccurredAtAsc(fromUtc, toUtc)) {
            if (!"IN".equals(e.getDirection())) continue;
            if (!channel.equals(e.getChannel())) continue;
            if (!"CASHIER".equals(e.getSource())) continue;
            if (!"RF-REVENUE".equals(e.getSubject())) continue;
            String store = e.getStoreCode();
            if (!DataScope.canReadStore(store)) continue;
            if (sc != null && !sc.equals(store)) continue;
            String idem = e.getIdemKey();
            if (idem == null || !idem.startsWith(ORDER_PAID_PREFIX)) continue;
            String orderNo = idem.substring(ORDER_PAID_PREFIX.length());
            if (orderNo.isBlank()) continue;
            long amt = e.getAmount() == null ? 0L : e.getAmount();
            if (amt <= 0) continue;
            sysOrders.computeIfAbsent(store + "|" + orderNo, k -> new long[2])[0] += amt;
            long[] s = sysByStore.computeIfAbsent(store, k -> new long[2]);
            s[0] += amt;
            s[1]++;
        }

        // ---------- 账单侧：pay_channel_bill 该渠道月内账单 ----------
        // key = 门店|订单号；value = [SUCCESS 金额, SUCCESS 笔数, REFUND 金额, REFUND 笔数, FAILED 笔数]
        Map<String, long[]> billOrders = new TreeMap<>();
        // 每店累计：[SUCCESS 金额, SUCCESS 笔数, REFUND 金额, REFUND 笔数, FAILED 笔数, 手续费额]
        Map<String, long[]> billByStore = new TreeMap<>();
        // 手续费行（order_no 空）：[行数, 手续费额]
        long[] feeOnly = new long[2];
        long billRowCount = 0;
        for (PayChannelBill b : billRepo.findByBillTimeBetweenOrderByBillTimeAsc(fromUtc, toUtc)) {
            if (!channel.equals(b.getChannelCode())) continue;
            String store = b.getStoreCode();
            if (!DataScope.canReadStore(store)) continue;
            if (sc != null && !sc.equals(store)) continue;
            billRowCount++;
            long[] bs = billByStore.computeIfAbsent(store, k -> new long[6]);
            long txn = b.getTxnAmount() == null ? 0L : b.getTxnAmount();
            long fee = b.getFeeAmount() == null ? 0L : b.getFeeAmount();
            bs[5] += fee;
            String status = b.getBillStatus();
            String orderNo = b.getOrderNo();
            if (orderNo == null || orderNo.isBlank()) {
                // 手续费/结算扣费行：无勾兑键，单列汇总不参与逐单比对
                feeOnly[0]++;
                feeOnly[1] += fee;
                continue;
            }
            long[] bo = billOrders.computeIfAbsent(store + "|" + orderNo, k -> new long[5]);
            switch (status == null ? "" : status) {
                case "SUCCESS" -> { bo[0] += txn; bo[1]++; bs[0] += txn; bs[1]++; }
                case "REFUND" -> { bo[2] += txn; bo[3]++; bs[2] += txn; bs[3]++; }
                case "FAILED" -> { bo[4]++; bs[4]++; }
                default -> { /* 非法状态导入侧已整批拒绝，此处保守忽略 */ }
            }
        }

        // ---------- 逐单勾兑 ----------
        List<Map<String, Object>> matched = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();   // 系统有账单无（漏单）
        List<Map<String, Object>> extra = new ArrayList<>();     // 账单有系统无（多单/未入账）
        List<Map<String, Object>> amountMismatch = new ArrayList<>();
        long matchedCount = 0, missingCount = 0, extraCount = 0, mismatchCount = 0;
        long matchedSysFen = 0;
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(sysOrders.keySet());
        allKeys.addAll(billOrders.keySet());
        for (String key : allKeys) {
            int bar = key.indexOf('|');
            String store = key.substring(0, bar);
            String orderNo = key.substring(bar + 1);
            long[] sys = sysOrders.get(key);
            long[] bill = billOrders.get(key);
            long sysAmt = sys == null ? 0L : sys[0];
            long billSucc = bill == null ? 0L : bill[0];
            long billRefund = bill == null ? 0L : bill[2];
            long billFailed = bill == null ? 0L : bill[4];

            if (sys != null && bill != null && billSucc > 0 && sysAmt == billSucc) {
                matchedCount++;
                matchedSysFen += sysAmt;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("storeCode", store);
                r.put("orderNo", orderNo);
                r.put("sysFen", sysAmt);
                r.put("sysYuan", yuan(sysAmt));
                r.put("billSuccessFen", billSucc);
                r.put("billRefundFen", billRefund);
                r.put("billFailedCount", billFailed);
                matched.add(r);
            } else if (sys != null && (bill == null || billSucc == 0L)) {
                // 系统已记收款，账单无成功交易行（漏单；账单仅有退款/失败行亦归入此并注明）
                missingCount++;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("storeCode", store);
                r.put("orderNo", orderNo);
                r.put("sysFen", sysAmt);
                r.put("sysYuan", yuan(sysAmt));
                r.put("billRefundFen", billRefund);
                r.put("billFailedCount", billFailed);
                r.put("reason", bill == null
                        ? "渠道结算单无此订单：客户已付款但渠道账单缺失，请核对导出范围/结算批次"
                        : "系统已收款但账单仅有退款/失败行、无成功交易，请核对该订单渠道实际状态");
                missing.add(r);
            } else if (sys == null || sysAmt == 0L) {
                // 账单有成功交易，系统无收款分录（多单/未入账；纯退款单在此口径下也归此，备注说明）
                extraCount++;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("storeCode", store);
                r.put("orderNo", orderNo);
                r.put("billSuccessFen", billSucc);
                r.put("billSuccessYuan", yuan(billSucc));
                r.put("billRefundFen", billRefund);
                r.put("billFailedCount", billFailed);
                r.put("reason", billSucc > 0
                        ? "渠道账单有成功交易但系统无收款分录：未入账或挂错门店/渠道，请逐笔核查收银台与订单"
                        : "账单仅有退款/失败行而系统无收款分录：退款核销走三方对账口径，请核对原退款单号");
                extra.add(r);
            } else {
                // 两边都有成功金额但不一致
                mismatchCount++;
                long diff = sysAmt - billSucc;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("storeCode", store);
                r.put("orderNo", orderNo);
                r.put("sysFen", sysAmt);
                r.put("sysYuan", yuan(sysAmt));
                r.put("billSuccessFen", billSucc);
                r.put("billSuccessYuan", yuan(billSucc));
                r.put("diffFen", diff);
                r.put("diffYuan", yuan(diff));
                r.put("billRefundFen", billRefund);
                r.put("billFailedCount", billFailed);
                amountMismatch.add(r);
            }
        }

        // ---------- 门店汇总 + 店名解析 ----------
        Set<String> stores = new TreeSet<>();
        stores.addAll(sysByStore.keySet());
        stores.addAll(billByStore.keySet());
        Map<String, String> storeNames = stores.isEmpty()
                ? Map.of() : aggregation.resolveStoreNames(new ArrayList<>(stores));

        List<Map<String, Object>> storeRows = new ArrayList<>();
        long sysTotalFen = 0, sysTotalCount = 0;
        long billSuccTotal = 0, billSuccCount = 0, billRefundTotal = 0, billRefundCount = 0;
        long billFailedTotal = 0, feeTotal = 0;
        for (String store : stores) {
            long[] s = sysByStore.getOrDefault(store, new long[2]);
            long[] b = billByStore.getOrDefault(store, new long[6]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("storeCode", store);
            row.put("storeName", storeNames.getOrDefault(store, store));
            row.put("sysFen", s[0]);
            row.put("sysYuan", yuan(s[0]));
            row.put("sysCount", s[1]);
            row.put("billSuccessFen", b[0]);
            row.put("billSuccessYuan", yuan(b[0]));
            row.put("billSuccessCount", b[1]);
            row.put("billRefundFen", b[2]);
            row.put("billRefundYuan", yuan(b[2]));
            row.put("billRefundCount", b[3]);
            row.put("billFailedCount", b[4]);
            row.put("feeFen", b[5]);
            row.put("feeYuan", yuan(b[5]));
            storeRows.add(row);
            sysTotalFen += s[0];
            sysTotalCount += s[1];
            billSuccTotal += b[0];
            billSuccCount += b[1];
            billRefundTotal += b[2];
            billRefundCount += b[3];
            billFailedTotal += b[4];
            feeTotal += b[5];
        }

        boolean billsAvailable = billRowCount > 0;
        boolean clean = missingCount == 0 && extraCount == 0 && mismatchCount == 0;
        boolean matchedAll = clean && billsAvailable;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("month", monthFirst.toString());
        r.put("channel", channel);
        r.put("channelLabel", channelLabel(channel));
        r.put("store", sc == null ? "ALL" : sc);
        r.put("currency", "CNY");
        r.put("unit", "分");
        r.put("billsAvailable", billsAvailable);
        r.put("matched", matchedAll);
        r.put("sysFen", sysTotalFen);
        r.put("sysYuan", yuan(sysTotalFen));
        r.put("sysCount", sysTotalCount);
        r.put("billSuccessFen", billSuccTotal);
        r.put("billSuccessYuan", yuan(billSuccTotal));
        r.put("billSuccessCount", billSuccCount);
        r.put("billRefundFen", billRefundTotal);
        r.put("billRefundYuan", yuan(billRefundTotal));
        r.put("billRefundCount", billRefundCount);
        r.put("billFailedCount", billFailedTotal);
        r.put("billRowCount", billRowCount);
        r.put("feeFen", feeTotal);
        r.put("feeYuan", yuan(feeTotal));
        r.put("feeOnlyRowCount", feeOnly[0]);
        r.put("feeOnlyFen", feeOnly[1]);
        r.put("feeOnlyYuan", yuan(feeOnly[1]));
        r.put("matchedCount", matchedCount);
        r.put("matchedFen", matchedSysFen);
        r.put("matchedYuan", yuan(matchedSysFen));
        r.put("missingCount", missingCount);
        r.put("extraCount", extraCount);
        r.put("amountMismatchCount", mismatchCount);
        r.put("stores", storeRows);
        r.put("matchedOrders", matched);
        r.put("missingOrders", missing);
        r.put("extraOrders", extra);
        r.put("amountMismatchOrders", amountMismatch);
        r.put("message", buildMessage(matchedAll, billsAvailable, channel,
                missingCount, extraCount, mismatchCount, billRefundCount));
        log.info("[ChannelReconcile] {} channel={} store={} matched={} 漏单 {} 多单 {} 金额不符 {}（账单行 {}）",
                monthFirst, channel, sc == null ? "ALL" : sc, matchedAll,
                missingCount, extraCount, mismatchCount, billRowCount);
        return r;
    }

    private String buildMessage(boolean matchedAll, boolean billsAvailable, String channel,
                                long missing, long extra, long mismatch, long refundCount) {
        String label = channelLabel(channel);
        if (!billsAvailable) {
            return "本月「" + label + "」尚无导入账单，无法做账实结论：系统侧收款已归集，"
                    + "请先从" + label + "商户平台导出结算单 CSV，经「导入账单」整批导入后再勾兑；"
                    + "本核对不覆盖现金/余额渠道，退款核销差异以三方对账口径为准。";
        }
        if (matchedAll) {
            String refundNote = refundCount > 0
                    ? "账单含退款行 " + refundCount + " 笔（系统退款落账为 OUT 分录，按三方对账口径核销，不参与本收款勾兑）；"
                    : "";
            return "本月「" + label + "」账实勾兑通过：系统收款与账单成功交易逐单匹配，无漏单/多单/金额不符；"
                    + refundNote + "手续费行已单列汇总供结算参考，账单仅作对账依据，不据此补造分录。";
        }
        return "本月「" + label + "」勾兑存在差异：漏单（系统有账单无）" + missing + " 笔、"
                + "多单/未入账（账单有系统无）" + extra + " 笔、金额不符 " + mismatch + " 笔；"
                + "请按差异清单逐笔核查——漏单重点核对渠道结算单导出范围，多单重点核对收银台是否漏记/挂错门店。"
                + "退款行不参与本收款勾兑（以三方对账为准）；账单只作对账依据，绝不据账单补造实付分录。";
    }

    private static String channelLabel(String channel) {
        return switch (channel) {
            case "wxpay" -> "微信支付";
            case "alipay" -> "支付宝";
            case "transfer" -> "银行转账";
            default -> channel;
        };
    }

    /** 分 → 元（两位小数）。 */
    private static double yuan(long fen) {
        return Math.round(fen / 100.0 * 100.0) / 100.0;
    }
}
