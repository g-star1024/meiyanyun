package com.meiyun.finance;

import com.meiyun.security.DataScope;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 财务运维报表 CSV 导出（B8，DESIGN §七B7 移交项）。
 *
 * <p>只读导出四张运维报表：封账台账、三方对账（按日门店明细）、资金分录台账、四类成本汇总。
 * 数据全部薄调现有聚合（{@link SettlementService#list} / {@link TripartiteReconcileService#tripartite}
 * / {@link FinanceAggregationService#ledger} / cost_allocation 聚合），不重写任何聚合逻辑，
 * 数据域收敛（DataScope）与权限与页面端点同源继承——页面看不到的门店，导出同样没有。
 *
 * <p>CSV 约定：UTF-8 BOM 头（Excel 直接打开中文不乱码）、中文表头、CRLF 行分隔、
 * 金额一律「元」（库内 Long 分 /100 两位小数；台账流水聚合层本就给元）、
 * 字段含逗号/引号/换行时双引号包裹且引号双写（RFC 4180）。
 */
@Service
public class FinanceExportService {

    /** 一份导出报表：下载文件名 + 已带 BOM 的 UTF-8 字节。 */
    public record CsvReport(String filename, byte[] content) {}

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final Map<String, String> SUBJECT_CN = Map.of(
            "RF-REVENUE", "营收收入",
            "RF-REFUND", "退款支出",
            "RF-DEPOSIT", "预收划扣",
            "TK-MATERIAL", "耗材成本",
            "TK-LOSS", "报损成本",
            "TK-DEPRECIATION", "折旧分摊",
            "TK-LABOR", "人工成本");

    private static final Map<String, String> SOURCE_CN = Map.of(
            "CASHIER", "收银",
            "ERP", "系统划扣",
            "MANUAL", "人工录入");

    private static final Map<String, String> INVOICE_TYPE_CN = Map.of(
            "NORMAL", "增值税普通发票",
            "SPECIAL", "增值税专用发票",
            "ELECTRONIC", "增值税电子普通发票");

    private static final Map<String, String> INVOICE_CATEGORY_CN = Map.of(
            "SERVICE", "医疗服务",
            "PRODUCT", "产品销售",
            "MEMBERSHIP", "会员卡/疗程");

    private static final Map<String, String> INVOICE_STATUS_CN = Map.of(
            "DRAFT", "待开票",
            "ISSUED", "已开票",
            "VOIDED", "已作废",
            "RED_FLUSHED", "已红冲");

    private final SettlementService settlementService;
    private final TripartiteReconcileService tripartiteService;
    private final FinanceAggregationService aggregation;
    private final CostAllocationRepository costRepo;
    private final FinConfigService configService;

    public FinanceExportService(SettlementService settlementService,
                                TripartiteReconcileService tripartiteService,
                                FinanceAggregationService aggregation,
                                CostAllocationRepository costRepo,
                                FinConfigService configService) {
        this.settlementService = settlementService;
        this.tripartiteService = tripartiteService;
        this.aggregation = aggregation;
        this.costRepo = costRepo;
        this.configService = configService;
    }

    // ==================== 四张报表 ====================

    /** 封账台账导出（权限 finance:settlement:view，与 GET /settlement 同源）。 */
    public CsvReport exportSettlement(String periodType, String periodKey, String storeCode) {
        List<Map<String, Object>> rows = settlementService.list(periodType, periodKey, storeCode);
        StringBuilder sb = new StringBuilder();
        row(sb, "封账单号", "期间类型", "期间", "门店编码", "门店名称", "状态",
                "分录笔数", "净额(元)", "备注", "封账人", "封账时间");
        for (Map<String, Object> r : rows) {
            row(sb, r.get("settlementId"), periodTypeCn(str(r.get("periodType"))), r.get("periodKey"),
                    r.get("storeCode"), r.get("storeName"), statusCn(str(r.get("status"))),
                    r.get("entryCount"), fen(r.get("netAmountFen")), nz(r.get("memo")),
                    nz(r.get("closedBy")), ts(r.get("closedAt")));
        }
        return report("封账台账", sb);
    }

    /** 三方对账门店明细导出（按日；现金渠道未接入时现金列留空，与页面诚实降级一致）。 */
    @SuppressWarnings("unchecked")
    public CsvReport exportTripartite(String date, String storeCode) {
        Map<String, Object> res = tripartiteService.tripartite(date, storeCode);
        String day = str(res.get("date"));
        StringBuilder sb = new StringBuilder();
        row(sb, "对账日期", "门店编码", "门店名称", "经营净额(元)", "落账净额(元)", "账账差异(元)",
                "其中期末成本(元)", "未解差异(元)", "现金实点(元)", "现金差异(元)", "现金交接单数",
                "订单数", "退款数", "划扣对数", "退卡数", "落账分录数", "是否核对通过");
        Object stores = res.get("stores");
        if (stores instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> r = (Map<String, Object>) o;
                row(sb, day, r.get("storeCode"), r.get("storeName"),
                        fen(r.get("bizNetFen")), fen(r.get("postedNetFen")), fen(r.get("netDiffFen")),
                        fen(r.get("manualCostFen")), fen(r.get("unexplainedDiffFen")),
                        fen(r.get("cashHandoverFen")), fen(r.get("cashDiffFen")), nz(r.get("cashTicketCount")),
                        r.get("orderCount"), r.get("refundCount"), r.get("writeoffPairCount"),
                        r.get("cardCancelCount"), r.get("postedEntryCount"),
                        Boolean.TRUE.equals(r.get("matched")) ? "是" : "否");
            }
        }
        return report("三方对账-" + day, sb);
    }

    /** 资金分录台账导出（金额聚合层已为「元」；过滤条件与 GET /ledger 一致）。 */
    public CsvReport exportLedger(String storeCode, String from, String to) {
        List<FinanceViewDTO.LedgerEntry> entries = aggregation.ledger(storeCode, from, to);
        StringBuilder sb = new StringBuilder();
        row(sb, "分录号", "日期", "门店名称", "科目", "方向", "金额(元)", "渠道", "来源",
                "单据类型", "单据号", "摘要", "混合支付");
        for (FinanceViewDTO.LedgerEntry e : entries) {
            row(sb, e.id(), e.date(), e.store(),
                    SUBJECT_CN.getOrDefault(e.subject(), e.subject()),
                    directionCn(e.direction()), yuanObj(e.amount()), nz(e.channel()),
                    SOURCE_CN.getOrDefault(e.source(), e.source()),
                    e.refType(), e.refNo(), e.memo(),
                    Boolean.TRUE.equals(e.mixed()) ? "是" : "");
        }
        String range = (from == null || from.isBlank() ? "all" : from)
                + "_" + (to == null || to.isBlank() ? "all" : to);
        return report("资金台账-" + range, sb);
    }

    /** 四类成本汇总导出（门店 × 月份；聚合口径与 GET /cost 完全一致，金额分→元）。 */
    public CsvReport exportCost(String storeCode, String month) {
        Specification<CostAllocation> spec = DataScope.storeSpec("storeCode");
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        if (month != null && !month.isBlank()) {
            LocalDate m = LocalDate.parse(month).withDayOfMonth(1);
            spec = spec.and((root, q, cb) -> cb.equal(root.get("periodMonth"), m));
        }
        List<CostAllocation> all = costRepo.findAll(spec,
                Sort.by(Sort.Order.asc("periodMonth"), Sort.Order.asc("storeCode")));

        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (CostAllocation c : all) {
            String key = c.getPeriodMonth().toString() + "|" + c.getStoreCode();
            Map<String, Object> row = byKey.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("periodMonth", c.getPeriodMonth().toString());
                m.put("storeCode", c.getStoreCode());
                m.put("material", 0L);
                m.put("loss", 0L);
                m.put("depreciation", 0L);
                m.put("labor", 0L);
                m.put("total", 0L);
                return m;
            });
            String field = switch (c.getCostType()) {
                case "MATERIAL" -> "material";
                case "LOSS" -> "loss";
                case "DEPRECIATION" -> "depreciation";
                case "LABOR" -> "labor";
                default -> null;
            };
            if (field != null) {
                row.merge(field, c.getAmount(), (a, b) -> (Long) a + (Long) b);
                row.merge("total", c.getAmount(), (a, b) -> (Long) a + (Long) b);
            }
        }

        List<String> codes = new ArrayList<>(byKey.values().stream()
                .map(r -> str(r.get("storeCode"))).distinct().toList());
        Map<String, String> names = aggregation.resolveStoreNames(codes);

        StringBuilder sb = new StringBuilder();
        row(sb, "月份", "门店编码", "门店名称", "耗材成本(元)", "报损成本(元)",
                "折旧分摊(元)", "人工成本(元)", "合计(元)");
        for (Map<String, Object> r : byKey.values()) {
            String sc = str(r.get("storeCode"));
            row(sb, r.get("periodMonth"), sc, names.getOrDefault(sc, sc),
                    fen(r.get("material")), fen(r.get("loss")),
                    fen(r.get("depreciation")), fen(r.get("labor")), fen(r.get("total")));
        }
        return report("成本汇总" + (month == null || month.isBlank() ? "" : "-" + month.substring(0, 7)), sb);
    }

    /**
     * 发票台账导出（B24 卡1；权限 finance:invoice:view，与 GET /invoices 同源同过滤同数据域）。
     * 列表薄调 {@link FinConfigService#listInvoices}，视图金额本就是「元」，税率为 BigDecimal 原值。
     * 草稿（DRAFT）未开票时 issuedAt 为空，开票日期列留空；关联订单多单号以顿号连接。
     */
    public CsvReport exportInvoices(String storeCode, String status, String type, String keyword) {
        List<Map<String, Object>> rows = configService.listInvoices(storeCode, status, type, keyword);
        StringBuilder sb = new StringBuilder();
        row(sb, "票号", "票种", "项目类别", "状态", "发票抬头", "税号", "购方名称",
                "价税合计(元)", "税额(元)", "税率", "门店编码", "门店名称", "关联订单",
                "开票人", "复核人", "开票日期", "备注");
        for (Map<String, Object> r : rows) {
            Object refs = r.get("orderRefs");
            String refText = "";
            if (refs instanceof List<?> list && !list.isEmpty()) {
                refText = list.stream().filter(java.util.Objects::nonNull)
                        .map(String::valueOf).collect(java.util.stream.Collectors.joining("、"));
            }
            Object rate = r.get("taxRate");
            String rateText = rate == null ? "" : (rate + "（" + percent(rate) + "）");
            row(sb, r.get("invoiceNo"),
                    INVOICE_TYPE_CN.getOrDefault(str(r.get("type")), nz(r.get("type"))),
                    INVOICE_CATEGORY_CN.getOrDefault(str(r.get("category")), nz(r.get("category"))),
                    INVOICE_STATUS_CN.getOrDefault(str(r.get("status")), nz(r.get("status"))),
                    r.get("title"), r.get("taxNo"), r.get("buyerName"),
                    yuanObj(r.get("amount")), yuanObj(r.get("taxAmount")), rateText,
                    r.get("storeCode"), r.get("store"), refText,
                    r.get("operator"), r.get("reviewer"), ts(r.get("issuedAt")), nz(r.get("remark")));
        }
        return report("发票台账", sb);
    }

    // ==================== CSV 工具 ====================

    /** 税率小数（0.06）→ 百分号文本（6%），用于发票导出括注；无法解析时原样返回。 */
    private String percent(Object v) {
        try {
            java.math.BigDecimal bd = new java.math.BigDecimal(String.valueOf(v));
            return bd.multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
        } catch (NumberFormatException e) {
            return String.valueOf(v);
        }
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

    /** Long 分 → 元字符串（两位小数）；null 输出空列。 */
    private String fen(Object v) {
        if (v == null) return "";
        return String.format(Locale.ROOT, "%.2f", ((Number) v).longValue() / 100.0);
    }

    /** 台账流水金额本就是「元」Double，统一两位小数；null 空列。 */
    private String yuanObj(Object v) {
        if (v == null) return "";
        return String.format(Locale.ROOT, "%.2f", ((Number) v).doubleValue());
    }

    /** 时间值（OffsetDateTime toString 带 T）转可读串；null 空列。 */
    private String ts(Object v) {
        if (v == null) return "";
        return String.valueOf(v).replace('T', ' ').replaceAll("\\.\\d+", "")
                .replaceAll("([+-]\\d{2}:?\\d{2}|Z)$", "").trim();
    }

    private String nz(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String periodTypeCn(String t) {
        if ("DAY".equals(t)) return "日结";
        if ("MONTH".equals(t)) return "月结";
        return t == null ? "" : t;
    }

    private String statusCn(String s) {
        if ("CLOSED".equals(s)) return "已封账";
        return s == null ? "" : s;
    }

    private String directionCn(String d) {
        if ("IN".equals(d)) return "入账";
        if ("OUT".equals(d)) return "出账";
        return d == null ? "" : d;
    }

    /** 拼装最终报表：中文文件名 + 导出时间戳，内容加 UTF-8 BOM。 */
    private CsvReport report(String name, StringBuilder sb) {
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[3 + body.length];
        all[0] = (byte) 0xEF;
        all[1] = (byte) 0xBB;
        all[2] = (byte) 0xBF;
        System.arraycopy(body, 0, all, 3, body.length);
        return new CsvReport(name + "-" + LocalDateTime.now().format(TS_FMT) + ".csv", all);
    }
}
