package com.meiyun.finance;

import com.meiyun.security.DataScope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
        Map<String, String> names = aggregation.resolveStoreNames(
                visible.stream().map(RevenueMonthly::getStoreCode).toList());

        List<String> headers = List.of("门店", "营收(元)", "成本(元)", "毛利率(%)", "环比(%)");
        List<List<String>> rows = new ArrayList<>();
        for (RevenueMonthly r : visible) {
            long rev = r.getRevenue() == null ? 0L : r.getRevenue();
            long cost = r.getCost() == null ? 0L : r.getCost();
            BigDecimal rate = r.getGrossRate();
            Long prev = prevRev.get(r.getStoreCode());
            String mom = prev == null || prev == 0L ? "—"
                    : String.format(Locale.ROOT, "%+.2f%%", (rev - prev) * 100.0 / prev);
            rows.add(List.of(
                    names.getOrDefault(r.getStoreCode(), r.getStoreCode()),
                    fen(rev), fen(cost),
                    rate == null ? "" : rate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%",
                    mom));
        }
        return new ReportData(headers, rows);
    }

    private String fen(long v) {
        return String.format(Locale.ROOT, "%.2f", v / 100.0);
    }
}
