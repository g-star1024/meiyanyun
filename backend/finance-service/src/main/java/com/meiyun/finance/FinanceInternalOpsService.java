package com.meiyun.finance;

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
import java.util.TreeMap;

/**
 * finance 内部运维操作（B3 双跑切换工具，DESIGN §7）：
 * <ul>
 *   <li><b>ledger-diff</b>：按 subject+direction 维度比对「读时聚合净额（txn 业务表实时聚合）」vs
 *       「落账净额（fund_entry）」，差异 fen 全 0 才允许切换读源；人工调平分录（bizType=ADJUST）
 *       不参与口径比对，单列 adjust 统计；</li>
 *   <li><b>backfill</b>：历史回填，拉取交易域四表 bundle 按与 FinanceEventPublisher 逐字一致的
 *       idemKey / memo / 科目 / 渠道规则构建 FundEntryCmd 批量落账，idem_key 幂等可重复执行；
 *       occurredAt 传业务发生时间（实时链路取 finance 受理时刻，跨日偏差以双跑校对暴露）。</li>
 * </ul>
 * 仅经内部端点（X-Internal-Token / internal:fund-write）调用，不面向页面。
 */
@Service
public class FinanceInternalOpsService {

    private static final Logger log = LoggerFactory.getLogger(FinanceInternalOpsService.class);

    private final FinanceAggregationService aggregation;
    private final FundEntryService fundEntryService;
    private final FundEntryRepository entryRepo;

    public FinanceInternalOpsService(FinanceAggregationService aggregation,
                                     FundEntryService fundEntryService,
                                     FundEntryRepository entryRepo) {
        this.aggregation = aggregation;
        this.fundEntryService = fundEntryService;
        this.entryRepo = entryRepo;
    }

    // ==================== 双跑校对 ====================

    /**
     * 聚合净额 vs 落账净额比对。from/to 为 yyyy-MM-dd（UTC 半开 [from, to+1d)）。
     * 聚合侧金额由「元」Math.round 精确还原为「分」再与落账侧 Long 分比对。
     */
    public Map<String, Object> ledgerDiff(String from, String to) {
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        OffsetDateTime fromUtc = fromDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime toUtc = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

        // 聚合侧（system 身份 DataScope 全量放行）：subject|direction → [count, fen]
        Map<String, long[]> aggGroups = new TreeMap<>();
        long aggNet = 0;
        for (FinanceViewDTO.LedgerEntry e : aggregation.ledger(null, from, to)) {
            long fen = Math.round(e.amount() * 100.0);
            accumulate(aggGroups, e.subject(), e.direction(), fen);
            aggNet += "IN".equals(e.direction()) ? fen : -fen;
        }

        // 落账侧：fund_entry 区间扫描，ADJUST 人工调平单列
        Map<String, long[]> postedGroups = new TreeMap<>();
        long postedNet = 0;
        long adjustCount = 0;
        long adjustFen = 0;
        for (FundEntry e : entryRepo.findByOccurredAtBetweenOrderByOccurredAtAsc(fromUtc, toUtc)) {
            if ("ADJUST".equals(e.getBizType())) {
                adjustCount++;
                adjustFen += e.getAmount();
                continue;
            }
            accumulate(postedGroups, e.getSubject(), e.getDirection(), e.getAmount());
            postedNet += "IN".equals(e.getDirection()) ? e.getAmount() : -e.getAmount();
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        boolean matched = true;
        for (String key : unionKeys(aggGroups, postedGroups)) {
            String[] parts = key.split("\\|");
            long[] a = aggGroups.getOrDefault(key, new long[]{0, 0});
            long[] p = postedGroups.getOrDefault(key, new long[]{0, 0});
            long diffFen = p[1] - a[1];
            if (diffFen != 0) matched = false;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subject", parts[0]);
            row.put("direction", parts[1]);
            row.put("aggCount", a[0]);
            row.put("aggFen", a[1]);
            row.put("postedCount", p[0]);
            row.put("postedFen", p[1]);
            row.put("diffFen", diffFen);
            groups.add(row);
        }
        long netDiff = postedNet - aggNet;
        if (netDiff != 0) matched = false;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("from", fromDate.toString());
        r.put("to", toDate.toString());
        r.put("matched", matched);
        r.put("aggNetFen", aggNet);
        r.put("postedNetFen", postedNet);
        r.put("netDiffFen", netDiff);
        r.put("adjustCount", adjustCount);
        r.put("adjustFen", adjustFen);
        r.put("groups", groups);
        r.put("message", matched
                ? "双跑校对通过：聚合净额与落账净额一致（差异 0 分），可切换读源"
                : "双跑校对存在差异：请按分组 diffFen 排查口径，差异清零前不得切换读源");
        log.info("ledger-diff {} ~ {} matched={} netDiff={} 分（人工调平 {} 笔 / {} 分）",
                fromDate, toDate, matched, netDiff, adjustCount, adjustFen);
        return r;
    }

    private void accumulate(Map<String, long[]> groups, String subject, String direction, long fen) {
        long[] v = groups.computeIfAbsent(subject + "|" + direction, k -> new long[]{0, 0});
        v[0]++;
        v[1] += fen;
    }

    private List<String> unionKeys(Map<String, long[]> a, Map<String, long[]> b) {
        Map<String, Boolean> all = new TreeMap<>();
        a.keySet().forEach(k -> all.put(k, true));
        b.keySet().forEach(k -> all.put(k, true));
        return new ArrayList<>(all.keySet());
    }

    // ==================== 历史回填 ====================

    /**
     * 历史回填：按交易域四表流水生成资金分录并幂等落账，可重复执行。
     * 返回扫描/构建/新增/幂等跳过计数。
     */
    public Map<String, Object> backfill(String from, String to) {
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");

        Map<String, Object> bundle = aggregation.fetchFlows(null, from, to);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) bundle.getOrDefault("orders", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refunds = (List<Map<String, Object>>) bundle.getOrDefault("refunds", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> writeoffs = (List<Map<String, Object>>) bundle.getOrDefault("writeoffs", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cardCancels = (List<Map<String, Object>>) bundle.getOrDefault("cardCancels", List.of());

        // 订单号 → 主渠道码（与聚合侧同口径），供退款 ORIGINAL 反查
        Map<String, String> orderChannel = new LinkedHashMap<>();
        for (Map<String, Object> o : orders) {
            String ch = mapPayMethod(str(o.get("payMethod")));
            if (ch != null) orderChannel.put(str(o.get("orderNo")), ch);
        }

        List<FundEntryCmd> cmds = new ArrayList<>();
        for (Map<String, Object> o : orders) {
            long amount = longOf(o.get("amount"));
            if (amount <= 0) continue;
            String orderNo = str(o.get("orderNo"));
            String memo = "订单收款 · " + nz(str(o.get("project")), orderNo);
            if (Boolean.TRUE.equals(o.get("mixed"))) memo = memo + "（混合支付）";
            cmds.add(new FundEntryCmd("ORDER-PAID:" + orderNo, orderNo, "ORDER",
                    "RF-REVENUE", "IN", amount, mapPayMethod(str(o.get("payMethod"))),
                    "CASHIER", "ORDER", str(o.get("storeCode")), memo, str(o.get("createdAt"))));
        }
        for (Map<String, Object> rf : refunds) {
            long refundAmt = longOf(rf.get("refundAmt"));
            if (refundAmt <= 0) continue;
            String txnNo = str(rf.get("txnNo"));
            cmds.add(new FundEntryCmd("REFUND-PAID:" + txnNo, txnNo, "REFUND",
                    "RF-REFUND", "OUT", refundAmt,
                    resolveRefundChannel(str(rf.get("channel")), str(rf.get("orderNo")), orderChannel),
                    "CASHIER", "REFUND", str(rf.get("storeCode")),
                    "退款支出 · " + nz(str(rf.get("customerName")), txnNo), str(rf.get("createdAt"))));
        }
        for (Map<String, Object> w : writeoffs) {
            // 双守卫对齐聚合侧：cardNo 非空（卡扣划扣）且金额 > 0；整单核销/纯扣次无资金动账
            String cardNo = str(w.get("cardNo"));
            long amount = longOf(w.get("amount"));
            if (cardNo == null || cardNo.isBlank() || amount <= 0) continue;
            String woId = str(w.get("writeoffId"));
            String project = nz(str(w.get("project")), woId);
            String occurredAt = str(w.get("createdAt"));
            String storeCode = str(w.get("storeCode"));
            cmds.add(new FundEntryCmd("WRITEOFF-DEPOSIT:" + woId, woId, "WRITEOFF",
                    "RF-DEPOSIT", "OUT", amount, null, "ERP", "WRITEOFF",
                    storeCode, "卡划扣（预收转出）· " + project, occurredAt));
            cmds.add(new FundEntryCmd("WRITEOFF-REVENUE:" + woId, woId, "WRITEOFF",
                    "RF-REVENUE", "IN", amount, null, "ERP", "WRITEOFF",
                    storeCode, "划扣确认收入 · " + project, occurredAt));
        }
        for (Map<String, Object> c : cardCancels) {
            String txnNo = str(c.get("txnNo"));
            String namePart = nz(str(c.get("customerName")), txnNo);
            String storeCode = str(c.get("storeCode"));
            String occurredAt = str(c.get("createdAt"));
            String channel = resolveCcChannel(str(c.get("channel")));
            long refundAmt = longOf(c.get("refundAmt"));
            long balance = longOf(c.get("balance"));
            long fee = longOf(c.get("fee"));
            if (refundAmt > 0) {
                cmds.add(new FundEntryCmd("CARD-CANCEL-REFUND:" + txnNo, txnNo, "REFUND",
                        "RF-REFUND", "OUT", refundAmt, channel, "CASHIER", "REFUND",
                        storeCode, "退款支出 · " + namePart, occurredAt));
            }
            if (balance > 0) {
                cmds.add(new FundEntryCmd("CARD-CANCEL-DEPOSIT:" + txnNo, txnNo, "REFUND",
                        "RF-DEPOSIT", "OUT", balance, null, "ERP", "REFUND",
                        storeCode, "退卡冲预收 · " + namePart, occurredAt));
            }
            if (fee > 0) {
                cmds.add(new FundEntryCmd("CARD-CANCEL-FEE:" + txnNo, txnNo, "REFUND",
                        "RF-REVENUE", "IN", fee, null, "ERP", "REFUND",
                        storeCode, "退卡违约金收入 · " + namePart, occurredAt));
            }
        }

        long inserted = 0;
        long duplicated = 0;
        if (!cmds.isEmpty()) {
            List<Map<String, Object>> results = fundEntryService.postEntries(cmds, "system");
            for (Map<String, Object> res : results) {
                if (Boolean.TRUE.equals(res.get("duplicated"))) duplicated++;
                else inserted++;
            }
        }

        Map<String, Object> scanned = new LinkedHashMap<>();
        scanned.put("orders", orders.size());
        scanned.put("refunds", refunds.size());
        scanned.put("writeoffs", writeoffs.size());
        scanned.put("cardCancels", cardCancels.size());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("from", fromDate.toString());
        r.put("to", toDate.toString());
        r.put("scanned", scanned);
        r.put("builtEntries", cmds.size());
        r.put("inserted", inserted);
        r.put("duplicated", duplicated);
        r.put("message", "历史回填完成：新增落账 " + inserted + " 条，幂等跳过 " + duplicated + " 条（可重复执行）");
        log.info("backfill {} ~ {} 扫描 orders={} refunds={} writeoffs={} cardCancels={} 构建 {} 条，新增 {} / 幂等 {}",
                fromDate, toDate, orders.size(), refunds.size(), writeoffs.size(), cardCancels.size(),
                cmds.size(), inserted, duplicated);
        return r;
    }

    // ==================== 工具 ====================

    private LocalDate parseDate(String s, String field) {
        if (s == null || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "日期参数 " + field + " 不能为空（格式 yyyy-MM-dd，如 2026-09-05）");
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "日期参数 " + field + " 格式非法，需 yyyy-MM-dd（如 2026-09-05）：" + s);
        }
    }

    /** 订单收款渠道码直通；未知/空保守 null（与 FinanceAggregationService.mapPayMethod 同口径）。 */
    private String mapPayMethod(String payMethod) {
        if (payMethod == null) return null;
        return switch (payMethod) {
            case "cash", "card", "wxpay", "alipay", "balance" -> payMethod;
            default -> null;
        };
    }

    /** 退款渠道：CASH→cash、TRANSFER→transfer、ORIGINAL→反查原单主渠道（查不到 null）。 */
    private String resolveRefundChannel(String channel, String orderNo, Map<String, String> orderChannel) {
        if (channel == null) return null;
        return switch (channel) {
            case "CASH" -> "cash";
            case "TRANSFER" -> "transfer";
            case "ORIGINAL" -> orderNo == null ? null : orderChannel.get(orderNo);
            default -> null;
        };
    }

    /** 退卡渠道：CASH→cash、TRANSFER→transfer；退卡无原单号，ORIGINAL 保守 null。 */
    private String resolveCcChannel(String channel) {
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

    private static String nz(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
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
}
