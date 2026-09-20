package com.meiyun.finance;

import com.meiyun.security.DataScope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ReportDataCollector {

    public record ReportData(List<String> headers, List<List<String>> rows) {}

    private static final Map<String, String> CHANNEL_CN = Map.of(
            "cash", "现金",
            "card", "银行卡",
            "wxpay", "微信支付",
            "alipay", "支付宝",
            "balance", "余额",
            "transfer", "银行转账");

    private static final List<String> CH_ORDER =
            List.of("cash", "card", "wxpay", "alipay", "balance", "transfer", "other");

    private final FinanceAggregationService aggregation;
    private final RevenueMonthlyRepository revRepo;

    public ReportDataCollector(FinanceAggregationService aggregation, RevenueMonthlyRepository revRepo) {
        this.aggregation = aggregation;
        this.revRepo = revRepo;
    }

    public ReportData collect(String templateId, String period) {
        return switch (templateId) {
            case "R01" -> collectR01(period);
            case "R02" -> collectR02(period);
            case "R05" -> collectR05(period);
            case "R07" -> collectR07(period);
            default -> throw new IllegalArgumentException("unsupported template: " + templateId);
        };
    }

    private ReportData collectR01(String day) {
        List<FinanceViewDTO.LedgerEntry> entries = aggregation.ledger(null, day, day);
        Map<String, long[]> byKey = new LinkedHashMap<>();
        for (FinanceViewDTO.LedgerEntry e : entries) {
            if (!"CASHIER".equals(e.source())) continue;
            String ch = e.channel() == null || e.channel().isBlank() ? "other" : e.channel();
            long[] acc = byKey.computeIfAbsent(e.store() + "|" + ch, k -> new long[3]);
            long fen = e.amount() == null ? 0L : Math.round(e.amount() * 100);
            if ("RF-REVENUE".equals(e.subject()) && "IN".equals(e.direction())) {
                acc[0] += fen;
                acc[2]++;
            } else if ("RF-REFUND".equals(e.subject()) && "OUT".equals(e.direction())) {
                acc[1] += fen;
            }
        }
        List<Map.Entry<String, long[]>> sorted = new ArrayList<>(byKey.entrySet());
        sorted.sort(Comparator
                .comparing((Map.Entry<String, long[]> en) -> en.getKey().split("\\|", 2)[0])
                .thenComparingInt(en -> {
                    String ch = en.getKey().split("\\|", 2)[1];
                    int i = CH_ORDER.indexOf(ch);
                    return i < 0 ? CH_ORDER.size() : i;
                }));

        List<String> headers = List.of("门店", "支付方式", "营收(元)", "客单价(元)", "笔数");
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> en : sorted) {
            String[] key = en.getKey().split("\\|", 2);
            long[] acc = en.getValue();
            long net = acc[0] - acc[1];
            String avg = acc[2] == 0 ? "0.00"
                    : String.format(Locale.ROOT, "%.2f", net / 100.0 / acc[2]);
            rows.add(List.of(key[0],
                    CHANNEL_CN.getOrDefault(key[1], "其他"),
                    fen(net), avg, String.valueOf(acc[2])));
        }
        return new ReportData(headers, rows);
    }

    private ReportData collectR02(String month) {
        LocalDate m = LocalDate.parse(month + "-01");
        List<RevenueMonthly> visible = revRepo.findByPeriodMonthOrderByStoreCodeAsc(m).stream()
                .filter(r -> DataScope.canReadStore(r.getStoreCode()))
                .toList();
        Map<String, Long> prevRev = new HashMap<>();
        for (RevenueMonthly p : revRepo.findByPeriodMonthOrderByStoreCodeAsc(m.minusMonths(1))) {
            prevRev.put(p.getStoreCode(), p.getRevenue() == null ? 0L : p.getRevenue());
        }
        List<String> storeCodes = visible.stream().map(RevenueMonthly::getStoreCode).toList();
        Map<String, String> names = aggregation.resolveStoreNames(storeCodes);
        Map<String, String> regions = aggregation.resolveStoreRegions(storeCodes);

        List<String> headers = List.of("区域", "门店", "营收(元)", "成本(元)", "毛利率(%)", "环比(%)");
        List<List<String>> rows = new ArrayList<>();
        for (RevenueMonthly r : visible) {
            long rev = r.getRevenue() == null ? 0L : r.getRevenue();
            long cost = r.getCost() == null ? 0L : r.getCost();
            BigDecimal rate = r.getGrossRate();
            Long prev = prevRev.get(r.getStoreCode());
            String mom = prev == null || prev == 0L ? "—"
                    : String.format(Locale.ROOT, "%+.2f%%", (rev - prev) * 100.0 / prev);
            rows.add(List.of(
                    regions.getOrDefault(r.getStoreCode(), ""),
                    names.getOrDefault(r.getStoreCode(), r.getStoreCode()),
                    fen(rev), fen(cost),
                    rate == null ? "" : rate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%",
                    mom));
        }
        return new ReportData(headers, rows);
    }

    private ReportData collectR05(String month) {
        List<Map<String, Object>> allCards = aggregation.fetchCards(null);
        LocalDate today = LocalDate.now();
        LocalDate expiryThreshold = today.plusDays(30);
        Map<String, long[]> byKey = new LinkedHashMap<>();
        for (Map<String, Object> c : allCards) {
            String cardType = c.get("cardType") == null ? "" : c.get("cardType").toString();
            if (!"COURSE".equals(cardType)) continue;
            String sc = c.get("storeCode") == null ? "" : c.get("storeCode").toString();
            if (!sc.isBlank() && !DataScope.canReadStore(sc)) continue;
            String item = c.get("cardItem") == null ? "未命名" : c.get("cardItem").toString();
            long[] acc = byKey.computeIfAbsent(sc + "|" + item, k -> new long[5]);
            int total = c.get("totalTimes") instanceof Number n ? n.intValue() : 0;
            int remain = c.get("remainTimes") instanceof Number n ? n.intValue() : 0;
            acc[0] += total;
            acc[1] += remain;
            String status = c.get("status") == null ? "" : c.get("status").toString();
            String expiresRaw = c.get("expiresAt") == null ? null : c.get("expiresAt").toString();
            if ("在用".equals(status) && expiresRaw != null) {
                try {
                    LocalDate exp = OffsetDateTime.parse(expiresRaw)
                            .atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
                    if (!exp.isAfter(expiryThreshold)) acc[2]++;
                } catch (Exception ignored) {}
            }
            acc[3] = 1;
        }
        List<String> storeCodes = byKey.keySet().stream()
                .map(k -> k.split("\\|", 2)[0]).filter(s -> !s.isBlank()).distinct().toList();
        Map<String, String> names = aggregation.resolveStoreNames(storeCodes);
        List<String> headers = List.of("门店", "项目/卡项", "总次数", "已消耗", "剩余次数", "核销率(%)", "即将到期(张)");
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> en : byKey.entrySet()) {
            String[] key = en.getKey().split("\\|", 2);
            long[] acc = en.getValue();
            String storeName = names.getOrDefault(key[0], key[0].isBlank() ? "未知" : key[0]);
            long consumed = acc[0] - acc[1];
            String rate = acc[0] == 0 ? "—"
                    : String.format(Locale.ROOT, "%.1f", consumed * 100.0 / acc[0]);
            rows.add(List.of(storeName, key[1],
                    String.valueOf(acc[0]), String.valueOf(consumed), String.valueOf(acc[1]),
                    rate, String.valueOf(acc[2])));
        }
        return new ReportData(headers, rows);
    }

    private ReportData collectR07(String month) {
        List<Map<String, Object>> rows = aggregation.fetchRefundSummary(month);
        List<String> storeCodes = rows.stream()
                .map(r -> r.get("storeCode") == null ? "" : r.get("storeCode").toString())
                .filter(s -> !s.isBlank())
                .distinct().toList();
        Map<String, String> names = aggregation.resolveStoreNames(storeCodes);
        List<String> headers = List.of("门店", "退款原因", "退款笔数", "退款金额(元)", "平均处理时长(天)");
        List<List<String>> data = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String sc = r.get("storeCode") == null ? "" : r.get("storeCode").toString();
            if (!sc.isBlank() && !DataScope.canReadStore(sc)) continue;
            String storeName = names.getOrDefault(sc, sc.isBlank() ? "未知" : sc);
            String reason = r.get("reason") == null ? "未标注" : r.get("reason").toString();
            long count = r.get("count") instanceof Number n ? n.longValue() : 0L;
            long totalAmt = r.get("totalRefundAmt") instanceof Number n ? n.longValue() : 0L;
            double avgDays = r.get("avgProcessingDays") instanceof Number n ? n.doubleValue() : 0.0;
            String avgStr = avgDays == 0.0 ? "—" : String.format(Locale.ROOT, "%.1f", avgDays);
            data.add(List.of(storeName, reason, String.valueOf(count), fen(totalAmt), avgStr));
        }
        return new ReportData(headers, data);
    }

    private String fen(long v) {
        return String.format(Locale.ROOT, "%.2f", v / 100.0);
    }
}
