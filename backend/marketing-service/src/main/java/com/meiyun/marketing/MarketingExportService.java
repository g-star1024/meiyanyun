package com.meiyun.marketing;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 营销总览导出（P5-B94 D2-D5 首卡 CSV；棒④卡3 04 L162 XLSX/PDF 格式增强，复刻 finance Report 三格式链）。
 *
 * <p>与 {@code GET /api/marketing/stats/overview} 同源同口径——薄调
 * {@link MarketingStatsService#overview()} 六块聚合结果，统一装配七节中间结构（CSV/XLSX/PDF 三格式共享）：
 * ①KPI 汇总 ②优惠券明细 ③活动明细 ④推送效果 ⑤转化漏斗 ⑥渠道排行 ⑦近 6 月趋势。
 * 金额库内 bigint「分」→ 导出「元」两位小数（finance 七端点同约定）；
 * 比率/百分比/ROI 原值输出（与页面适配层同源，表头注明口径）。
 *
 * <p>三格式约定：
 * CSV——UTF-8 BOM 头（Excel/WPS 双击直开中文不乱码）、中文表头、CRLF 行分隔、RFC 4180 转义；
 * XLSX——easyexcel 七 sheet（每节一 sheet，sheet 名=节名）；
 * PDF——openpdf 横向 A4，节标题 + 表格（STSong-Light/UniGB-UCS2-H 中文字体，复刻 ReportPdfBuilder）。
 * 导出只读，审计 MARKETING_DASH+EXPORT（payload 含 filename/format/sections/rows）。
 */
@Service
public class MarketingExportService {

    /** 一份导出报表：下载文件名 + MIME + 内容字节（CSV 已带 BOM）。 */
    public record ExportReport(String filename, String mediaType, byte[] content) {}

    /** 一个报表节：节名 + 表头 + 数据行（单元格已按导出口径字符串化，null 归一为 ""）。 */
    private record Section(String title, List<String> headers, List<List<String>> rows) {}

    /** 七节装配结果：节列表 + 明细行合计（KPI 节不计入，与首卡 CSV 口径一致） + 文件名基干。 */
    private record OverviewData(List<Section> sections, long dataRows, String baseName) {}

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int SECTION_COUNT = 7;
    private static final String MEDIA_CSV = "text/csv; charset=UTF-8";
    private static final String MEDIA_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MEDIA_PDF = "application/pdf";

    private final MarketingStatsService statsService;
    private final AuditRecorder audit;

    public MarketingExportService(MarketingStatsService statsService, AuditRecorder audit) {
        this.statsService = statsService;
        this.audit = audit;
    }

    /** 营销总览 CSV：文件名 marketing-overview-yyyyMMdd.csv。 */
    public ExportReport exportOverview() {
        OverviewData data = collectOverview();
        StringBuilder sb = new StringBuilder();
        for (Section s : data.sections()) {
            section(sb, s.title());
            row(sb, s.headers().toArray());
            for (List<String> r : s.rows()) {
                row(sb, r.toArray());
            }
        }
        String filename = data.baseName() + ".csv";
        recordAudit(filename, "CSV", data.dataRows());
        return new ExportReport(filename, MEDIA_CSV, withBom(sb));
    }

    /** 营销总览 XLSX：easyexcel 七 sheet（每节一 sheet，sheet 名=节名）。 */
    public ExportReport exportOverviewXlsx() {
        OverviewData data = collectOverview();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelWriter writer = EasyExcel.write(out).build();
        try {
            for (int i = 0; i < data.sections().size(); i++) {
                Section s = data.sections().get(i);
                List<List<String>> head = new ArrayList<>();
                for (String h : s.headers()) {
                    head.add(List.of(h));
                }
                List<List<Object>> sheetRows = new ArrayList<>();
                for (List<String> r : s.rows()) {
                    sheetRows.add(new ArrayList<>(r));
                }
                WriteSheet sheet = EasyExcel.writerSheet(i, s.title()).head(head).build();
                writer.write(sheetRows, sheet);
            }
        } finally {
            writer.finish();
        }
        String filename = data.baseName() + ".xlsx";
        recordAudit(filename, "XLSX", data.dataRows());
        return new ExportReport(filename, MEDIA_XLSX, out.toByteArray());
    }

    /** 营销总览 PDF：openpdf 横向 A4，节标题 + 表格（STSong 中文字体）。 */
    public ExportReport exportOverviewPdf() {
        OverviewData data = collectOverview();
        try {
            BaseFont bf = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font titleFont = new Font(bf, 12, Font.BOLD);
            Font headerFont = new Font(bf, 10, Font.BOLD);
            Font cellFont = new Font(bf, 9, Font.NORMAL);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
            PdfWriter.getInstance(doc, out);
            doc.open();
            for (Section s : data.sections()) {
                Paragraph p = new Paragraph(s.title(), titleFont);
                p.setSpacingBefore(10);
                p.setSpacingAfter(6);
                doc.add(p);
                PdfPTable table = new PdfPTable(s.headers().size());
                table.setWidthPercentage(100);
                for (String h : s.headers()) {
                    PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
                    cell.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
                    cell.setPadding(4);
                    table.addCell(cell);
                }
                for (List<String> r : s.rows()) {
                    for (String val : r) {
                        PdfPCell cell = new PdfPCell(new Phrase(val == null ? "" : val, cellFont));
                        cell.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
                        cell.setPadding(4);
                        table.addCell(cell);
                    }
                }
                doc.add(table);
            }
            doc.close();
            String filename = data.baseName() + ".pdf";
            recordAudit(filename, "PDF", data.dataRows());
            return new ExportReport(filename, MEDIA_PDF, out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("PDF 生成失败: " + e.getMessage(), e);
        }
    }

    // ==================== 七节装配（三格式共享，口径与首卡 CSV 逐字节一致） ====================

    @SuppressWarnings("unchecked")
    private OverviewData collectOverview() {
        Map<String, Object> ov = statsService.overview();
        Map<String, Object> coupon = (Map<String, Object>) ov.get("coupon");
        Map<String, Object> campaign = (Map<String, Object>) ov.get("campaign");
        Map<String, Object> push = (Map<String, Object>) ov.get("push");
        Map<String, Object> funnel = (Map<String, Object>) ov.get("funnel");
        Map<String, Object> channel = (Map<String, Object>) ov.get("channel");
        Map<String, Object> trend = (Map<String, Object>) ov.get("trend");

        List<Section> sections = new ArrayList<>();
        long rows = 0;

        // ① KPI 汇总（coupon/campaign/push 三块关键指标键值对；不计入明细行合计）
        List<List<String>> kpi = new ArrayList<>();
        kpi.add(pair("券模板数", coupon.get("couponKinds")));
        kpi.add(pair("券总库存", coupon.get("totalStock")));
        kpi.add(pair("累计发券(张)", coupon.get("totalIssued")));
        kpi.add(pair("累计核销(张)", coupon.get("totalUsed")));
        kpi.add(pair("核销率(0-1)", coupon.get("writeoffRate")));
        kpi.add(pair("发放批次", coupon.get("grantBatches")));
        kpi.add(pair("发放张数", coupon.get("grantedPcs")));
        kpi.add(pair("活动总数", campaign.get("campaignCount")));
        kpi.add(pair("进行中活动", campaign.get("runningCount")));
        kpi.add(List.of("总投放(元)", fen(campaign.get("totalSpent"))));
        kpi.add(List.of("总成交(元)", fen(campaign.get("totalActualAmount"))));
        kpi.add(List.of("总预算(元)", fen(campaign.get("totalBudget"))));
        kpi.add(List.of("总目标(元)", fen(campaign.get("totalTargetAmount"))));
        kpi.add(pair("新客数", campaign.get("totalNewCustomers")));
        kpi.add(pair("综合ROI(倍数)", campaign.get("overallRoi")));
        kpi.add(pair("目标达成率(0-1)", campaign.get("achieveRate")));
        kpi.add(pair("触达批次", push.get("sent")));
        kpi.add(pair("全域触达", push.get("delivered")));
        kpi.add(pair("互动量", push.get("clicked")));
        kpi.add(pair("成交单量", push.get("converted")));
        kpi.add(pair("CTR(%)", push.get("ctr")));
        kpi.add(pair("CVR(%)", push.get("cvr")));
        sections.add(new Section("KPI 汇总", List.of("指标", "值"), kpi));

        // ② 优惠券明细
        List<List<String>> couponRows = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) coupon.get("rows")) {
            couponRows.add(List.of(str(r.get("couponId")), str(r.get("couponName")), str(r.get("couponType")),
                    str(r.get("status")), str(r.get("totalQty")), str(r.get("issuedQty")), str(r.get("usedQty")),
                    str(r.get("writeoffRate")), str(r.get("campaignId"))));
            rows++;
        }
        sections.add(new Section("优惠券明细",
                List.of("券编号", "券名称", "类型", "状态", "总库存", "已发放", "已核销", "核销率(0-1)", "关联活动"),
                couponRows));

        // ③ 活动明细
        List<List<String>> campaignRows = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) campaign.get("rows")) {
            campaignRows.add(List.of(str(r.get("campaignId")), str(r.get("campaignName")), str(r.get("campaignType")),
                    str(r.get("status")), fen(r.get("spent")), fen(r.get("actualAmount")),
                    fen(r.get("budget")), fen(r.get("targetAmount")),
                    str(r.get("newCustomers")), str(r.get("roi"))));
            rows++;
        }
        sections.add(new Section("活动明细",
                List.of("活动编号", "活动名称", "类型", "状态", "投放(元)", "成交(元)",
                        "预算(元)", "目标(元)", "新客", "ROI(倍数)"),
                campaignRows));

        // ④ 推送效果（单行汇总）
        sections.add(new Section("推送效果",
                List.of("触达批次", "全域触达", "互动量", "成交单量", "CTR(%)", "CVR(%)"),
                List.of(List.of(str(push.get("sent")), str(push.get("delivered")), str(push.get("clicked")),
                        str(push.get("converted")), str(push.get("ctr")), str(push.get("cvr"))))));
        rows++;

        // ⑤ 转化漏斗
        List<List<String>> funnelRows = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) funnel.get("stages")) {
            funnelRows.add(List.of(str(s.get("label")), str(s.get("value")), str(s.get("ratio"))));
            rows++;
        }
        sections.add(new Section("转化漏斗", List.of("阶段", "数值", "相对上级转化率(%)"), funnelRows));

        // ⑥ 渠道排行
        List<List<String>> channelRows = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) channel.get("rows")) {
            channelRows.add(List.of(str(r.get("name")), fen(r.get("revenue")), fen(r.get("spent")),
                    str(r.get("deals")), str(r.get("leads")), str(r.get("roi"))));
            rows++;
        }
        sections.add(new Section("渠道排行",
                List.of("渠道", "营收(元)", "投放(元)", "成交单量", "线索量", "ROI(倍数)"), channelRows));

        // ⑦ 近 6 月趋势
        List<List<String>> trendRows = new ArrayList<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) trend.get("points")) {
            trendRows.add(List.of(str(p.get("month")), str(p.get("reach")), str(p.get("converted"))));
            rows++;
        }
        sections.add(new Section("近6月趋势", List.of("月份", "触达", "成交"), trendRows));

        return new OverviewData(sections, rows,
                "marketing-overview-" + LocalDateTime.now().format(DAY_FMT));
    }

    /** 审计 MARKETING_DASH+EXPORT：txnNo 沿用首卡 EXPORT-yyyyMMdd（追加式无唯一约束），payload 增 format 键。 */
    private void recordAudit(String filename, String format, long rows) {
        audit.record("MARKETING_DASH", "EXPORT-" + LocalDateTime.now().format(DAY_FMT),
                DataScope.currentActor(), "EXPORT",
                "{\"filename\":\"" + filename + "\",\"format\":\"" + format
                        + "\",\"sections\":" + SECTION_COUNT + ",\"rows\":" + rows + "}");
    }

    // ==================== CSV 工具（FinanceExportService 同构） ====================

    /** 节标题行：前置空行（首节除外）+ 「# 节名」。 */
    private void section(StringBuilder sb, String name) {
        if (sb.length() > 0) sb.append("\r\n");
        sb.append("# ").append(name).append("\r\n");
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

    /** 拼装 CSV 最终字节：内容加 UTF-8 BOM。 */
    private byte[] withBom(StringBuilder sb) {
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[3 + body.length];
        all[0] = (byte) 0xEF;
        all[1] = (byte) 0xBB;
        all[2] = (byte) 0xBF;
        System.arraycopy(body, 0, all, 3, body.length);
        return all;
    }

    // ==================== 单元格工具 ====================

    /** 任意值 → 字符串；null 归一为空串（与首卡 CSV 的 esc 口径一致）。 */
    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** KPI 键值对行（值经 str 归一）。 */
    private List<String> pair(String k, Object v) {
        return List.of(k, str(v));
    }

    /** Long 分 → 元字符串（两位小数）；null 输出空列。 */
    private String fen(Object v) {
        if (v == null) return "";
        return String.format(Locale.ROOT, "%.2f", ((Number) v).longValue() / 100.0);
    }
}
