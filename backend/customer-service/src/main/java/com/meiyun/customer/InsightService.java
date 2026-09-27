package com.meiyun.customer;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户洞察报告只读聚合（M3-19 / DESIGN-M3 §3 D5）：
 * summary 七键＋近 6 月趋势＋等级/渠道分布＋智能洞察四条＋高复购项目 TOP5，
 * JdbcTemplate 直读 customer/txn_order/ai_churn_prediction/nps_record 四张既有表
 * （T2 直读范式，照 B4 JourneyService 先例），零新表零迁移。
 *
 * <p>口径锚定既有报表（DESIGN §5 验收线「与 RFM/复购率交叉核对」）：
 * 有效单＝status='已收款'（RfmCalculator「聚合该客户已收款订单」与
 * InternalRepurchaseController「事实全部来自 status='已收款' 的 txn_order」同源）；
 * 复购率＝期内 ≥2 已收款单客户/期内有单客户；activeRate＝期内有单客户/总客户；
 * avgLtv＝期内已收款总额(元)/有单客户；nps＝期内（推荐者−贬损者）/总数×100；
 * churnRate（summary）＝AI 最新评分批次域内 high 占比（无批次→0）；
 * trend 月 churnRate＝月末时点 90 天无已收款成交沉默率（与 summary 口径不同，如实披露）。
 * 金额库内为分，出口 DTO 转元（全站金额口径）。</p>
 *
 * <p>主档读侧过滤横切：anonymized_at/merged_into 非空剔除（CustomerRepository 读侧过滤惯例）；
 * 门店数据域 SQL 等价 DataScope.storeSpec（SELF 域加 owner_staff_id＝本人），
 * txn_order/nps_record/ai_churn_prediction 经 customer JOIN 继承同一域裁剪
 * （nps 匿名未匹配 customer 的行无法归属门店，不入域内统计）。</p>
 *
 * <p>性能口径（DESIGN §6）：限时段（period 30d/90d/12m 默认 90d）；trend 固定近 6 月
 * （与 period 无关），沉默率回溯多取 90 天订单窗口，窗口外订单不拉。</p>
 */
@Service
public class InsightService {

    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> LEVEL_ORDER = List.of("普通", "银卡", "金卡", "钻石", "黑卡");
    private static final Map<String, String> LEVEL_COLORS = Map.of(
            "普通", "var(--c-text-3)", "银卡", "#9ca3af", "金卡", "#f59e0b",
            "钻石", "#6366f1", "黑卡", "#111827");

    public record InsightSummary(long totalCustomers, long newThisPeriod, double activeRate,
                                 double repurchaseRate, double churnRate, double avgLtv, double nps) {}

    public record InsightTrendRow(String month, long newCustomers, long activeCustomers,
                                  double repurchaseRate, double churnRate) {}

    public record LevelDistRow(String level, long count, double percent, String color) {}

    public record ChannelDistRow(String channel, long count, double percent) {}

    public record TopInsight(String icon, String tone, String title, String desc) {}

    public record RepurchaseItem(String name, long count, double rate, double amount) {}

    public record InsightView(String period, InsightSummary summary, List<InsightTrendRow> trend,
                              List<LevelDistRow> levelDist, List<ChannelDistRow> channelDist,
                              List<TopInsight> topInsights, List<RepurchaseItem> repurchaseItems) {}

    private final JdbcTemplate jdbc;

    public InsightService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public InsightView view(String period) {
        String p = switch (period == null ? "" : period) {
            case "30d", "90d", "12m" -> period;
            default -> "90d";
        };
        int days = switch (p) {
            case "30d" -> 30;
            case "12m" -> 365;
            default -> 90;
        };
        LocalDate today = LocalDate.now(BIZ_ZONE);
        OffsetDateTime from = today.minusDays(days).atStartOfDay(BIZ_ZONE).toOffsetDateTime();
        OffsetDateTime from90 = today.minusDays(90).atStartOfDay(BIZ_ZONE).toOffsetDateTime();
        OffsetDateTime from30 = today.minusDays(30).atStartOfDay(BIZ_ZONE).toOffsetDateTime();
        LoginUser u = DataScope.current();

        long totalCustomers = countCustomers(u, null);
        long newThisPeriod = countCustomers(u, from);

        // 期内已收款单明细（内存分组：活跃/复购/avgLtv/项目 TOP5）
        List<OrderRow> orders = periodOrders(u, from);
        Map<String, Integer> custOrderCnt = new HashMap<>();
        Map<String, ProjAgg> projs = new HashMap<>();
        long amountSum = 0;
        for (OrderRow o : orders) {
            custOrderCnt.merge(o.customerId(), 1, Integer::sum);
            amountSum += o.amount();
            if (o.project() != null && !o.project().isBlank()) {
                ProjAgg a = projs.computeIfAbsent(o.project(), k -> new ProjAgg());
                a.count++;
                a.amountSum += o.amount();
                a.custOrders.merge(o.customerId(), 1, Integer::sum);
            }
        }
        long activeCustomers = custOrderCnt.size();
        long repurchaseCustomers = custOrderCnt.values().stream().filter(n -> n >= 2).count();
        double activeRate = totalCustomers == 0 ? 0 : round1(activeCustomers * 100.0 / totalCustomers);
        double repurchaseRate = activeCustomers == 0 ? 0 : round1(repurchaseCustomers * 100.0 / activeCustomers);
        double avgLtv = activeCustomers == 0 ? 0 : round2(amountSum / 100.0 / activeCustomers);
        double churnRate = churnRateSummary(u);
        double nps = npsSummary(u, from);
        InsightSummary summary = new InsightSummary(totalCustomers, newThisPeriod, activeRate,
                repurchaseRate, churnRate, avgLtv, nps);

        List<InsightTrendRow> trend = buildTrend(u, today);
        List<LevelDistRow> levels = levelDist(u, totalCustomers);
        List<ChannelDistRow> channels = channelDist(u, totalCustomers);
        List<RepurchaseItem> items = topRepurchaseItems(projs);
        long silent = silentCount(u, from90);
        long detractors = detractors30(u, from30);
        List<TopInsight> insights = buildInsights(trend, items, channels, silent, totalCustomers, detractors);

        return new InsightView(p, summary, trend, levels, channels, insights, items);
    }

    /** 域内有效客户总数（from 非空时仅计期内新增）。 */
    private long countCustomers(LoginUser u, OffsetDateTime from) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM customer c
                 WHERE c.anonymized_at IS NULL AND c.merged_into IS NULL
                """);
        if (from != null) {
            sql.append(" AND c.created_at >= ?");
            params.add(from);
        }
        sql.append(customerStoreClause(u, params));
        Long n = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return n == null ? 0 : n;
    }

    /** 期内已收款单明细（customer_id/project/amount）。 */
    private List<OrderRow> periodOrders(LoginUser u, OffsetDateTime from) {
        List<Object> params = new ArrayList<>();
        params.add(from);
        String sql = """
                SELECT o.customer_id, o.project, o.amount
                  FROM txn_order o JOIN customer c ON c.customer_id = o.customer_id
                 WHERE c.anonymized_at IS NULL AND c.merged_into IS NULL
                   AND o.status = '已收款' AND o.created_at >= ?
                """ + customerStoreClause(u, params);
        return jdbc.query(sql, (rs, i) -> new OrderRow(rs.getString("customer_id"),
                rs.getString("project"), rs.getLong("amount")), params.toArray());
    }

    /** 近 6 月趋势：月新增/月活跃/月复购率/月末 90 天沉默率（窗口外多回溯 90 天订单）。 */
    private List<InsightTrendRow> buildTrend(LoginUser u, LocalDate today) {
        YearMonth firstYm = YearMonth.from(today).minusMonths(5);
        OffsetDateTime orderFrom = firstYm.atDay(1).minusDays(90).atStartOfDay(BIZ_ZONE).toOffsetDateTime();

        List<Object> custParams = new ArrayList<>();
        String custSql = """
                SELECT c.customer_id, c.created_at FROM customer c
                 WHERE c.anonymized_at IS NULL AND c.merged_into IS NULL
                """ + customerStoreClause(u, custParams);
        Map<String, OffsetDateTime> custCreated = new HashMap<>();
        jdbc.query(custSql, rs -> {
            custCreated.put(rs.getString("customer_id"), rs.getObject("created_at", OffsetDateTime.class));
        }, custParams.toArray());

        List<Object> ordParams = new ArrayList<>();
        ordParams.add(orderFrom);
        String ordSql = """
                SELECT o.customer_id, o.created_at
                  FROM txn_order o JOIN customer c ON c.customer_id = o.customer_id
                 WHERE c.anonymized_at IS NULL AND c.merged_into IS NULL
                   AND o.status = '已收款' AND o.created_at >= ?
                """ + customerStoreClause(u, ordParams);
        List<OrderAt> orders = jdbc.query(ordSql, (rs, i) -> new OrderAt(rs.getString("customer_id"),
                rs.getObject("created_at", OffsetDateTime.class)), ordParams.toArray());

        List<InsightTrendRow> rows = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            YearMonth ym = firstYm.plusMonths(i);
            OffsetDateTime mStartTs = ym.atDay(1).atStartOfDay(BIZ_ZONE).toOffsetDateTime();
            OffsetDateTime mEndTs = ym.plusMonths(1).atDay(1).atStartOfDay(BIZ_ZONE).toOffsetDateTime();
            OffsetDateTime silentFrom = mEndTs.minusDays(90);

            long newCnt = custCreated.values().stream()
                    .filter(t -> !t.isBefore(mStartTs) && t.isBefore(mEndTs)).count();
            Map<String, Integer> monthOrders = new HashMap<>();
            Set<String> hasOrderIn90 = new HashSet<>();
            for (OrderAt o : orders) {
                if (!o.at().isBefore(mStartTs) && o.at().isBefore(mEndTs)) {
                    monthOrders.merge(o.customerId(), 1, Integer::sum);
                }
                if (!o.at().isBefore(silentFrom) && o.at().isBefore(mEndTs)) {
                    hasOrderIn90.add(o.customerId());
                }
            }
            long activeCnt = monthOrders.size();
            long repurchaseCnt = monthOrders.values().stream().filter(n -> n >= 2).count();
            double repurchaseRate = activeCnt == 0 ? 0 : round1(repurchaseCnt * 100.0 / activeCnt);
            long alive = custCreated.values().stream().filter(t -> t.isBefore(mEndTs)).count();
            long silentCnt = custCreated.entrySet().stream()
                    .filter(e -> e.getValue().isBefore(mEndTs) && !hasOrderIn90.contains(e.getKey())).count();
            double churnRate = alive == 0 ? 0 : round1(silentCnt * 100.0 / alive);
            rows.add(new InsightTrendRow(ym.getMonthValue() + "月", newCnt, activeCnt, repurchaseRate, churnRate));
        }
        return rows;
    }

    /** 会员等级分布（全量域内有效客户；固定五档色，未知档原值兜底排尾）。 */
    private List<LevelDistRow> levelDist(LoginUser u, long totalCustomers) {
        List<Object> params = new ArrayList<>();
        String sql = """
                SELECT c.level, COUNT(*) AS cnt FROM customer c
                 WHERE c.anonymized_at IS NULL AND c.merged_into IS NULL
                """ + customerStoreClause(u, params) + " GROUP BY c.level";
        Map<String, Long> byLevel = new HashMap<>();
        jdbc.query(sql, rs -> {
            byLevel.put(rs.getString("level"), rs.getLong("cnt"));
        }, params.toArray());
        long sum = byLevel.values().stream().mapToLong(Long::longValue).sum();
        final long denom = totalCustomers > 0 ? totalCustomers : sum;
        List<LevelDistRow> rows = new ArrayList<>();
        for (String lv : LEVEL_ORDER) {
            Long cnt = byLevel.remove(lv);
            if (cnt != null && cnt > 0) {
                rows.add(new LevelDistRow(lv, cnt, pct(cnt, denom), LEVEL_COLORS.get(lv)));
            }
        }
        byLevel.forEach((lv, cnt) -> {
            if (cnt != null && cnt > 0) {
                rows.add(new LevelDistRow(lv, cnt, pct(cnt, denom), "var(--c-text-3)"));
            }
        });
        return rows;
    }

    /** 获客渠道分布（全量；英→中映射，空值归「未知」，按人数降序）。 */
    private List<ChannelDistRow> channelDist(LoginUser u, long totalCustomers) {
        List<Object> params = new ArrayList<>();
        String sql = """
                SELECT c.channel, COUNT(*) AS cnt FROM customer c
                 WHERE c.anonymized_at IS NULL AND c.merged_into IS NULL
                """ + customerStoreClause(u, params) + " GROUP BY c.channel";
        Map<String, Long> byText = new HashMap<>();
        jdbc.query(sql, rs -> {
            byText.merge(channelText(rs.getString("channel")), rs.getLong("cnt"), Long::sum);
        }, params.toArray());
        long sum = byText.values().stream().mapToLong(Long::longValue).sum();
        final long denom = totalCustomers > 0 ? totalCustomers : sum;
        List<ChannelDistRow> rows = new ArrayList<>(byText.size());
        byText.forEach((ch, cnt) -> rows.add(new ChannelDistRow(ch, cnt, pct(cnt, denom))));
        rows.sort((a, b) -> Long.compare(b.count(), a.count()));
        return rows;
    }

    /** summary churnRate：AI 最新评分批次域内 high 占比（无批次→0）。 */
    private double churnRateSummary(LoginUser u) {
        List<Object> params = new ArrayList<>();
        String sql = """
                SELECT p.risk_level, COUNT(*) AS cnt
                  FROM ai_churn_prediction p JOIN customer c ON c.customer_id = p.customer_id
                 WHERE c.anonymized_at IS NULL AND c.merged_into IS NULL
                   AND p.batch_no = (SELECT MAX(batch_no) FROM ai_churn_prediction)
                """ + customerStoreClause(u, params) + " GROUP BY p.risk_level";
        long[] agg = new long[2];
        jdbc.query(sql, rs -> {
            long cnt = rs.getLong("cnt");
            agg[1] += cnt;
            if ("high".equals(rs.getString("risk_level"))) {
                agg[0] += cnt;
            }
        }, params.toArray());
        return agg[1] == 0 ? 0 : round1(agg[0] * 100.0 / agg[1]);
    }

    /** summary nps：期内（推荐者−贬损者）/总数×100（无记录→0）。 */
    private double npsSummary(LoginUser u, OffsetDateTime from) {
        List<Object> params = new ArrayList<>();
        params.add(from);
        String sql = """
                SELECT n.category, COUNT(*) AS cnt
                  FROM nps_record n JOIN customer c ON c.customer_id = n.customer_id
                 WHERE c.anonymized_at IS NULL AND c.merged_into IS NULL
                   AND n.created_at >= ?
                """ + customerStoreClause(u, params) + " GROUP BY n.category";
        long[] agg = new long[3];
        jdbc.query(sql, rs -> {
            long cnt = rs.getLong("cnt");
            agg[2] += cnt;
            if ("PROMOTER".equals(rs.getString("category"))) {
                agg[0] += cnt;
            } else if ("DETRACTOR".equals(rs.getString("category"))) {
                agg[1] += cnt;
            }
        }, params.toArray());
        return agg[2] == 0 ? 0 : round1((agg[0] - agg[1]) * 100.0 / agg[2]);
    }

    /** 90 天无已收款成交的域内有效客户数（沉睡口径，含从未成交）。 */
    private long silentCount(LoginUser u, OffsetDateTime from90) {
        List<Object> params = new ArrayList<>();
        params.add(from90);
        String sql = """
                SELECT COUNT(*) FROM customer c
                 WHERE c.anonymized_at IS NULL AND c.merged_into IS NULL
                   AND NOT EXISTS (SELECT 1 FROM txn_order o
                                    WHERE o.customer_id = c.customer_id
                                      AND o.status = '已收款' AND o.created_at >= ?)
                """ + customerStoreClause(u, params);
        Long n = jdbc.queryForObject(sql, Long.class, params.toArray());
        return n == null ? 0 : n;
    }

    /** 近 30 天 NPS 贬损者条数。 */
    private long detractors30(LoginUser u, OffsetDateTime from30) {
        List<Object> params = new ArrayList<>();
        params.add(from30);
        String sql = """
                SELECT COUNT(*)
                  FROM nps_record n JOIN customer c ON c.customer_id = n.customer_id
                 WHERE c.anonymized_at IS NULL AND c.merged_into IS NULL
                   AND n.created_at >= ? AND n.category = 'DETRACTOR'
                """ + customerStoreClause(u, params);
        Long n = jdbc.queryForObject(sql, Long.class, params.toArray());
        return n == null ? 0 : n;
    }

    /** 高复购项目 TOP5：按期内已收款单数降序；rate＝该项目 ≥2 单客户/有单客户。 */
    private List<RepurchaseItem> topRepurchaseItems(Map<String, ProjAgg> projs) {
        return projs.entrySet().stream()
                .map(e -> {
                    ProjAgg a = e.getValue();
                    long repurchaseCusts = a.custOrders.values().stream().filter(n -> n >= 2).count();
                    double rate = a.custOrders.isEmpty() ? 0 : round1(repurchaseCusts * 100.0 / a.custOrders.size());
                    return new RepurchaseItem(e.getKey(), a.count, rate, round2(a.amountSum / 100.0));
                })
                .sorted((x, y) -> Long.compare(y.count(), x.count()))
                .limit(5)
                .toList();
    }

    /** 智能洞察四条（固定槽位 icon/tone 值域与前端 CSS 类契约一致，文案数值真实计算）。 */
    private List<TopInsight> buildInsights(List<InsightTrendRow> trend, List<RepurchaseItem> items,
                                           List<ChannelDistRow> channels, long silent,
                                           long totalCustomers, long detractors) {
        List<TopInsight> out = new ArrayList<>(4);
        double first = trend.isEmpty() ? 0 : trend.get(0).repurchaseRate();
        double last = trend.isEmpty() ? 0 : trend.get(trend.size() - 1).repurchaseRate();
        double diff = round1(last - first);
        boolean up = diff >= 0;
        String proj = items.isEmpty() ? "" : "，TOP 复购项目「" + items.get(0).name() + "」";
        out.add(new TopInsight("trend-up", up ? "success" : "warning",
                up ? "复购率近 6 个月上升" : "复购率近 6 个月回落",
                "从 " + first + "% 至 " + last + "%（" + (up ? "+" : "") + diff + " 个百分点）" + proj + "。"));
        out.add(new TopInsight("alert", "warning",
                "90 天沉睡客户 " + String.format("%,d", silent) + " 人",
                "占域内有效客户 " + pct(silent, totalCustomers) + "%（90 天无已收款成交），建议触发沉睡唤醒任务。"));
        if (channels.isEmpty()) {
            out.add(new TopInsight("customer", "brand", "暂无获客渠道数据", "域内有效客户暂无渠道标记。"));
        } else {
            ChannelDistRow top = channels.get(0);
            out.add(new TopInsight("customer", "brand", "TOP 获客渠道：" + top.channel(),
                    "贡献 " + String.format("%,d", top.count()) + " 人，占域内有效客户 " + top.percent() + "%。"));
        }
        out.add(new TopInsight("bell", "danger",
                "近 30 天 NPS 贬损者 " + String.format("%,d", detractors) + " 条",
                "0-6 分评价 " + String.format("%,d", detractors) + " 条待跟进，建议优先回访安抚。"));
        return out;
    }

    /** 门店数据域 SQL 片段（等价 DataScope.storeSpec/ownedSpec 谓词语义）；参数追加进 params（顺序绑定）。 */
    private String customerStoreClause(LoginUser u, List<Object> params) {
        if (u == null || u.isSuper() || DataScope.SCOPE_GROUP.equals(u.scope())
                || DataScope.SCOPE_BRAND.equals(u.scope())) {
            return "";
        }
        if (DataScope.SCOPE_REGION.equals(u.scope())) {
            List<String> stores = u.stores();
            if (stores == null || stores.isEmpty()) {
                return "";
            }
            params.addAll(stores);
            return " AND c.store_code IN (" + String.join(",", Collections.nCopies(stores.size(), "?")) + ")";
        }
        // SELF / STORE：绑定本门店；异常账号（无 storeCode）永假不见数据（storePredicate 同语义）
        if (u.storeCode() == null || u.storeCode().isBlank()) {
            return " AND 1=0";
        }
        params.add(u.storeCode());
        String clause = " AND c.store_code = ?";
        if (DataScope.SCOPE_SELF.equals(u.scope()) && u.staffId() != null) {
            params.add(u.staffId());
            clause += " AND c.owner_staff_id = ?";
        }
        return clause;
    }

    private static String channelText(String channel) {
        if (channel == null || channel.isBlank()) {
            return "未知";
        }
        return switch (channel) {
            case "WALK_IN" -> "自然到店";
            case "REFERRAL" -> "转介绍";
            case "MARKETING" -> "线上营销";
            case "APPOINTMENT" -> "预约到店";
            default -> channel;
        };
    }

    private static double pct(long part, long total) {
        return total == 0 ? 0 : round1(part * 100.0 / total);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private record OrderRow(String customerId, String project, long amount) {}

    private record OrderAt(String customerId, OffsetDateTime at) {}

    /** 项目聚合中间态（内存分组用，不出本类）。 */
    private static final class ProjAgg {
        long count;
        long amountSum;
        final Map<String, Integer> custOrders = new HashMap<>();
    }
}
