package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 营销总览 CSV 导出（P5-B94 D2-D5，复刻 FinanceExportService 范式）。
 *
 * <p>与 {@code GET /api/marketing/stats/overview} 同源同口径——薄调
 * {@link MarketingStatsService#overview()} 六块聚合结果，单 CSV 分节输出：
 * ①KPI 汇总 ②优惠券明细 ③活动明细 ④推送效果 ⑤转化漏斗 ⑥渠道排行 ⑦近 6 月趋势。
 * 金额库内 bigint「分」→ 导出「元」两位小数（finance 七端点同约定）；
 * 比率/百分比/ROI 原值输出（与页面适配层同源，表头注明口径）。
 *
 * <p>CSV 约定：UTF-8 BOM 头（Excel/WPS 双击直开中文不乱码）、中文表头、CRLF 行分隔、
 * 字段含逗号/引号/换行时双引号包裹且引号双写（RFC 4180）。导出只读，审计 MARKETING_DASH+EXPORT。
 */
@Service
public class MarketingExportService {

    /** 一份导出报表：下载文件名 + 已带 BOM 的 UTF-8 字节。 */
    public record CsvReport(String filename, byte[] content) {}

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int SECTION_COUNT = 7;

    private final MarketingStatsService statsService;
    private final AuditRecorder audit;

    public MarketingExportService(MarketingStatsService statsService, AuditRecorder audit) {
        this.statsService = statsService;
        this.audit = audit;
    }

    /** 营销总览导出：文件名 marketing-overview-yyyyMMdd.csv，审计 {filename, sections, rows}。 */
    @SuppressWarnings("unchecked")
    public CsvReport exportOverview() {
        Map<String, Object> ov = statsService.overview();
        Map<String, Object> coupon = (Map<String, Object>) ov.get("coupon");
        Map<String, Object> campaign = (Map<String, Object>) ov.get("campaign");
        Map<String, Object> push = (Map<String, Object>) ov.get("push");
        Map<String, Object> funnel = (Map<String, Object>) ov.get("funnel");
        Map<String, Object> channel = (Map<String, Object>) ov.get("channel");
        Map<String, Object> trend = (Map<String, Object>) ov.get("trend");

        StringBuilder sb = new StringBuilder();
        long rows = 0;

        // ① KPI 汇总（coupon/campaign/push 三块关键指标键值对）
        section(sb, "KPI 汇总");
        row(sb, "指标", "值");
        row(sb, "券模板数", coupon.get("couponKinds"));
        row(sb, "券总库存", coupon.get("totalStock"));
        row(sb, "累计发券(张)", coupon.get("totalIssued"));
        row(sb, "累计核销(张)", coupon.get("totalUsed"));
        row(sb, "核销率(0-1)", coupon.get("writeoffRate"));
        row(sb, "发放批次", coupon.get("grantBatches"));
        row(sb, "发放张数", coupon.get("grantedPcs"));
        row(sb, "活动总数", campaign.get("campaignCount"));
        row(sb, "进行中活动", campaign.get("runningCount"));
        row(sb, "总投放(元)", fen(campaign.get("totalSpent")));
        row(sb, "总成交(元)", fen(campaign.get("totalActualAmount")));
        row(sb, "总预算(元)", fen(campaign.get("totalBudget")));
        row(sb, "总目标(元)", fen(campaign.get("totalTargetAmount")));
        row(sb, "新客数", campaign.get("totalNewCustomers"));
        row(sb, "综合ROI(倍数)", campaign.get("overallRoi"));
        row(sb, "目标达成率(0-1)", campaign.get("achieveRate"));
        row(sb, "触达批次", push.get("sent"));
        row(sb, "全域触达", push.get("delivered"));
        row(sb, "互动量", push.get("clicked"));
        row(sb, "成交单量", push.get("converted"));
        row(sb, "CTR(%)", push.get("ctr"));
        row(sb, "CVR(%)", push.get("cvr"));

        // ② 优惠券明细
        List<Map<String, Object>> couponRows = (List<Map<String, Object>>) coupon.get("rows");
        section(sb, "优惠券明细");
        row(sb, "券编号", "券名称", "类型", "状态", "总库存", "已发放", "已核销", "核销率(0-1)", "关联活动");
        for (Map<String, Object> r : couponRows) {
            row(sb, r.get("couponId"), r.get("couponName"), r.get("couponType"), r.get("status"),
                    r.get("totalQty"), r.get("issuedQty"), r.get("usedQty"),
                    r.get("writeoffRate"), r.get("campaignId"));
            rows++;
        }

        // ③ 活动明细
        List<Map<String, Object>> campaignRows = (List<Map<String, Object>>) campaign.get("rows");
        section(sb, "活动明细");
        row(sb, "活动编号", "活动名称", "类型", "状态", "投放(元)", "成交(元)",
                "预算(元)", "目标(元)", "新客", "ROI(倍数)");
        for (Map<String, Object> r : campaignRows) {
            row(sb, r.get("campaignId"), r.get("campaignName"), r.get("campaignType"), r.get("status"),
                    fen(r.get("spent")), fen(r.get("actualAmount")),
                    fen(r.get("budget")), fen(r.get("targetAmount")),
                    r.get("newCustomers"), r.get("roi"));
            rows++;
        }

        // ④ 推送效果（单行汇总）
        section(sb, "推送效果");
        row(sb, "触达批次", "全域触达", "互动量", "成交单量", "CTR(%)", "CVR(%)");
        row(sb, push.get("sent"), push.get("delivered"), push.get("clicked"),
                push.get("converted"), push.get("ctr"), push.get("cvr"));
        rows++;

        // ⑤ 转化漏斗
        List<Map<String, Object>> stages = (List<Map<String, Object>>) funnel.get("stages");
        section(sb, "转化漏斗");
        row(sb, "阶段", "数值", "相对上级转化率(%)");
        for (Map<String, Object> s : stages) {
            row(sb, s.get("label"), s.get("value"), s.get("ratio"));
            rows++;
        }

        // ⑥ 渠道排行
        List<Map<String, Object>> channelRows = (List<Map<String, Object>>) channel.get("rows");
        section(sb, "渠道排行");
        row(sb, "渠道", "营收(元)", "投放(元)", "成交单量", "线索量", "ROI(倍数)");
        for (Map<String, Object> r : channelRows) {
            row(sb, r.get("name"), fen(r.get("revenue")), fen(r.get("spent")),
                    r.get("deals"), r.get("leads"), r.get("roi"));
            rows++;
        }

        // ⑦ 近 6 月趋势
        List<Map<String, Object>> points = (List<Map<String, Object>>) trend.get("points");
        section(sb, "近6月趋势");
        row(sb, "月份", "触达", "成交");
        for (Map<String, Object> p : points) {
            row(sb, p.get("month"), p.get("reach"), p.get("converted"));
            rows++;
        }

        String filename = "marketing-overview-" + LocalDateTime.now().format(DAY_FMT) + ".csv";
        audit.record("MARKETING_DASH", "EXPORT-" + LocalDateTime.now().format(DAY_FMT),
                DataScope.currentActor(), "EXPORT",
                "{\"filename\":\"" + filename + "\",\"sections\":" + SECTION_COUNT + ",\"rows\":" + rows + "}");
        return report(filename, sb);
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

    /** Long 分 → 元字符串（两位小数）；null 输出空列。 */
    private String fen(Object v) {
        if (v == null) return "";
        return String.format(Locale.ROOT, "%.2f", ((Number) v).longValue() / 100.0);
    }

    /** 拼装最终报表：内容加 UTF-8 BOM。 */
    private CsvReport report(String filename, StringBuilder sb) {
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[3 + body.length];
        all[0] = (byte) 0xEF;
        all[1] = (byte) 0xBB;
        all[2] = (byte) 0xBF;
        System.arraycopy(body, 0, all, 3, body.length);
        return new CsvReport(filename, all);
    }
}
