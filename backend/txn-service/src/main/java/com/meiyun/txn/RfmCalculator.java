package com.meiyun.txn;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 客户价值模型（RFM + 忠诚 + 活跃）读时计算器。
 *
 * <p><b>不落库、不建表</b>：客户 360 打开时聚合该客户已收款订单即时计算，阈值见 {@code docs/RFM-RULES.md}。
 * 金额单位：订单 amount 为 bigint「分」，M 输出转「元」。阈值标定基于 seed 库真实分位数 + 医美行业惯例
 * （活跃 90 天 / 沉睡 180 天 / 流失 365 天；年消费 ≥4 次为高频）。
 */
@Component
public class RfmCalculator {

    /** 结果视图（全中文字段，评分是数字；无成交客户评分为 null）。 */
    public record RfmView(
            Integer rScore, Integer fScore, Integer mScore,
            Long recencyDays, Integer freq365, Double monetary365,
            Integer orders90, Long totalOrders, Integer tenureMonths,
            String loyaltyLevel, String activityLevel, String lifecycle, String segment,
            boolean transacted) {

        /** 无成交客户的静态结果。 */
        static RfmView empty() {
            return new RfmView(null, null, null, null, 0, 0.0, 0, 0L, null,
                    null, "未成交", "未成交", "未成交客户", false);
        }
    }

    /**
     * 计算客户价值视图。
     *
     * @param paidOrders 该客户全部已收款订单（按时间倒序或正序均可）
     * @param createdAt  客户建档时间（用于会员存续月数，可空）
     */
    public RfmView calc(List<TxnOrder> paidOrders, OffsetDateTime createdAt) {
        LocalDate today = LocalDate.now();
        // 仅统计已收款
        List<TxnOrder> paid = paidOrders.stream()
                .filter(o -> "已收款".equals(o.getStatus())).toList();
        if (paid.isEmpty()) {
            return RfmView.empty();
        }

        OffsetDateTime latest = paid.stream().map(TxnOrder::getCreatedAt)
                .max(OffsetDateTime::compareTo).orElse(null);
        long recencyDays = latest == null ? 365L
                : java.time.Duration.between(latest, OffsetDateTime.now()).toDays();

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime since365 = now.minusDays(365);
        OffsetDateTime since180 = now.minusDays(180);
        OffsetDateTime since90 = now.minusDays(90);

        int freq365 = 0; long cents365 = 0; int orders90 = 0;
        boolean has180 = false;
        for (TxnOrder o : paid) {
            OffsetDateTime t = o.getCreatedAt();
            if (t != null && !t.isBefore(since365)) {
                freq365++;
                cents365 += o.getAmount() == null ? 0 : o.getAmount();
            }
            if (t != null && !t.isBefore(since90)) orders90++;
            if (t != null && !t.isBefore(since180)) has180 = true;
        }
        double monetary365 = cents365 / 100.0;
        long totalOrders = paid.size();

        Integer tenureMonths = null;
        if (createdAt != null) {
            tenureMonths = (int) java.time.Period.between(createdAt.toLocalDate(), today).toTotalMonths();
        }

        int r = scoreR(recencyDays);
        int f = scoreF(freq365);
        int m = scoreM(monetary365);

        String loyalty = loyaltyLevel(totalOrders, tenureMonths);
        String activity = activityLevel(orders90, has180, freq365);
        String lifecycle = lifecycle(totalOrders, activity);
        String segment = segment(r, f, m);

        return new RfmView(r, f, m, recencyDays, freq365, Math.round(monetary365 * 100) / 100.0,
                orders90, totalOrders, tenureMonths, loyalty, activity, lifecycle, segment, true);
    }

    /** R：最近消费距今天数（越小越好）。 */
    private int scoreR(long days) {
        if (days <= 30) return 5;
        if (days <= 90) return 4;
        if (days <= 180) return 3;
        if (days <= 365) return 2;
        return 1;
    }

    /** F：近 365 天成交订单数。 */
    private int scoreF(int freq) {
        if (freq >= 8) return 5;
        if (freq >= 6) return 4;
        if (freq >= 4) return 3;
        if (freq >= 2) return 2;
        return 1;
    }

    /** M：近 365 天消费金额（元）。 */
    private int scoreM(double yuan) {
        if (yuan >= 15000) return 5;
        if (yuan >= 8000) return 4;
        if (yuan >= 4000) return 3;
        if (yuan >= 1000) return 2;
        return 1;
    }

    /** 忠诚度：累计成交单量 + 会员存续月数。 */
    private String loyaltyLevel(long totalOrders, Integer tenureMonths) {
        int months = tenureMonths == null ? 0 : tenureMonths;
        if (totalOrders >= 8 || months >= 24) return "高忠诚";
        if (totalOrders >= 4 || months >= 12) return "中忠诚";
        return "低忠诚";
    }

    /** 活跃度：近 90/180/365 天成交情况。 */
    private String activityLevel(int orders90, boolean has180, int freq365) {
        if (orders90 >= 1) return "活跃";
        if (has180) return "沉默";
        if (freq365 >= 1) return "沉睡";
        return "流失";
    }

    /** 生命周期：新客 → 活跃 → 沉默 → 沉睡 → 流失。 */
    private String lifecycle(long totalOrders, String activity) {
        if (totalOrders <= 1) return "新客";
        return activity;
    }

    /**
     * 八象限分层（全中文）。标准 RFM 二分：R 分 ≥4 为「近期」、F/M 分 ≥4 为「高」，否则为「低」，
     * 三维各高/低 2³=8 类全覆盖（中间分 3 归入「低」侧，避免漏判）。
     */
    private String segment(int r, int f, int m) {
        boolean rNear = r >= 4;
        boolean fHigh = f >= 4;
        boolean mHigh = m >= 4;

        if (rNear && fHigh && mHigh) return "重要价值客户";
        if (rNear && !fHigh && mHigh) return "重要发展客户";
        if (!rNear && fHigh && mHigh) return "重要保持客户";
        if (!rNear && !fHigh && mHigh) return "重要挽留客户";
        if (rNear && fHigh && !mHigh) return "一般价值客户";
        if (rNear && !fHigh && !mHigh) return "一般发展客户";
        if (!rNear && fHigh && !mHigh) return "一般保持客户";
        return "一般挽留客户";
    }
}
