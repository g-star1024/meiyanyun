package com.meiyun.finance;

import com.meiyun.security.DataScope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 报表 CSV 真实数据装配（B49 卡11）：独立第三组件——ReportService.preview（同步）
 * 与 ReportAsyncRunner（异步）共用，避免 Service↔Runner 循环依赖。
 *
 * <p><b>R01 门店营收日报（收银口径）</b>：仅 source=CASHIER 分录——
 * 营收净额 = Σ（RF-REVENUE/IN 订单收款）− Σ（RF-REFUND/OUT 全部实退）。
 * 订单退款与退卡实退同（subject,direction,source,refType）无法区分，故实退全口径扣减（如实标注）；
 * ERP 来源（划扣确认收入、退卡冲预收、违约金，channel=null）不计入收银口径。
 * 笔数 = RF-REVENUE 笔数；客单价 = 净营收/笔数（0 笔→"0.00"）。
 * LedgerEntry.store 已是店名、amount 已是「元」（聚合层统一），此处转分再累加防 double 漂移。
 *
 * <p><b>R02 月度经营分析</b>：revenue_monthly（金额「分」）按门店展开。
 * revRepo 直读不经 DataScope（对比 ledger() 内部逐行收敛），故此处手动 canReadStore 过滤；
 * 区域维度照卡4 group-overview「本端点不跨域 join」先例收窄（登记 backlog），
 * 环比仅 lookup 同店上月基数不输出，prev map 不过滤不泄露。
 *
 * <p>CSV 约定与 FinanceExportService 一致：UTF-8 BOM、CRLF、RFC 4180 转义、金额两位小数（Locale.ROOT）。
 */
@Component
public class ReportCsvBuilder {

    /** 一份报表数据：表头 + 数据行（字符串化完成）+ 已带 BOM 的 UTF-8 字节。 */
    public record CsvData(List<String> headers, List<List<String>> rows, byte[] content) {}

    /** 渠道码 → 中文（ChannelReconcileService 仅三码，报表需六码全映射故自建）。 */
    private static final Map<String, String> CHANNEL_CN = Map.of(
            "cash", "现金",
            "card", "银行卡",
            "wxpay", "微信支付",
            "alipay", "支付宝",
            "balance", "余额",
            "transfer", "银行转账");

    /** 渠道排序（固定次序，other 兜底最后）。 */
    private static final List<String> CH_ORDER =
            List.of("cash", "card", "wxpay", "alipay", "balance", "transfer", "other");

    private final FinanceAggregationService aggregation;
    private final RevenueMonthlyRepository revRepo;

    public ReportCsvBuilder(FinanceAggregationService aggregation, RevenueMonthlyRepository revRepo) {
        this.aggregation = aggregation;
        this.revRepo = revRepo;
    }

    /** 按模板分派装配（调用方已校验 SUPPORTED；default 防御）。 */
    public CsvData build(String templateId, String period) {
        return switch (templateId) {
            case "R01" -> buildR01(period);
            case "R02" -> buildR02(period);
            default -> throw new IllegalArgumentException("unsupported template: " + templateId);
        };
    }

    // ==================== R01 门店营收日报 ====================

    private CsvData buildR01(String day) {
        List<FinanceViewDTO.LedgerEntry> entries = aggregation.ledger(null, day, day);
        // key = 店名|渠道码 → [收款分, 实退分, 笔数]
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
        return csv(headers, rows);
    }

    // ==================== R02 月度经营分析报告 ====================

    private CsvData buildR02(String month) {
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
        return csv(headers, rows);
    }

    // ==================== CSV 工具（与 FinanceExportService 同规） ====================

    private CsvData csv(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        row(sb, headers.toArray());
        for (List<String> r : rows) {
            row(sb, r.toArray());
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[3 + body.length];
        all[0] = (byte) 0xEF;
        all[1] = (byte) 0xBB;
        all[2] = (byte) 0xBF;
        System.arraycopy(body, 0, all, 3, body.length);
        return new CsvData(headers, rows, all);
    }

    /** 写一行（表头/数据共用）：null 与空串输出空列，CRLF 结尾。 */
    private void row(StringBuilder sb, Object... vals) {
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(esc(vals[i]));
        }
        sb.append("\r\n");
    }

    /** RFC 4180 转义：含逗号/双引号/换行时整列双引号包裹，内部引号双写。 */
    private String esc(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** Long 分 → 元字符串（两位小数）。 */
    private String fen(long v) {
        return String.format(Locale.ROOT, "%.2f", v / 100.0);
    }

    // ==================== B56 验真工具（字节冻结口径） ====================

    /** 冻结文件名时刻统一锚东八区（生成即定，多次下载同名；不依赖容器默认时区）。 */
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** SHA-256 原始字节（含 BOM）→ 64 位小写 hex；finance 不依赖 meiyun-common，用 JDK 原生实现。 */
    static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 常量时间比较两段 hex（验真防时序侧信道，仿 meiyun-security AuthInterceptor token 比对）。 */
    static boolean hashEquals(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null
                || expectedHex.length() != actualHex.length()) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                actualHex.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 冻结下载文件名：模板名-period-生成时刻（+08:00）.csv。
     * 锚 job.createdAt 而非下载时刻，同任务重复下载同名同字节（backlog L123 字节冻结要求）。
     */
    static String downloadFileName(String templateName, String period, OffsetDateTime createdAt) {
        String ts = createdAt.atZoneSameInstant(CN_ZONE).format(FILE_TS);
        return templateName + "-" + period + "-" + ts + ".csv";
    }
}
