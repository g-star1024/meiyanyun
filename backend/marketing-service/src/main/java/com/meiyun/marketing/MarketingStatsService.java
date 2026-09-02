package com.meiyun.marketing;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M5-06 ROI 看板统计聚合（只读）。
 *
 * 数据口径（营销库内真实数据，金额 bigint 存「分」，前端适配层转元）：
 * - 发券：coupon_template.issuedQty 累计已发放、usedQty 累计已核销；
 *   coupon_grant.grantCount（status=GRANTED）为发放批次实际发放张数，二者互为印证。
 * - 活动转化：campaign.spent 投放额、actualAmount 成交额、newCustomers 新客；
 *   综合 ROI = 总成交额 / 总投放额（投放为 0 时 ROI 为 0，避免除零）。
 * 核销链路在 txn-service 订单域（writeoff_record），不回写券模板 usedQty，
 * 故 usedQty 当前恒为 0——统计如实返回，核销率真实反映「营销库内核销回传」现状。
 */
@Service
public class MarketingStatsService {

    private final CouponTemplateRepository couponRepo;
    private final CouponGrantRepository grantRepo;
    private final CampaignRepository campaignRepo;

    public MarketingStatsService(CouponTemplateRepository couponRepo,
                                 CouponGrantRepository grantRepo,
                                 CampaignRepository campaignRepo) {
        this.couponRepo = couponRepo;
        this.grantRepo = grantRepo;
        this.campaignRepo = campaignRepo;
    }

    public Map<String, Object> overview() {
        List<CouponTemplate> coupons = couponRepo.findAllByOrderByCreatedAtDesc();
        List<CouponGrant> grants = grantRepo.findAllByOrderByGrantedAtDesc();
        List<Campaign> campaigns = campaignRepo.findAllByOrderByCreatedAtDesc();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("coupon", couponStats(coupons, grants));
        resp.put("campaign", campaignStats(campaigns));
        return resp;
    }

    private Map<String, Object> couponStats(List<CouponTemplate> coupons, List<CouponGrant> grants) {
        int totalIssued = coupons.stream().mapToInt(c -> nz(c.getIssuedQty())).sum();
        int totalUsed = coupons.stream().mapToInt(c -> nz(c.getUsedQty())).sum();
        int totalStock = coupons.stream().mapToInt(c -> nz(c.getTotalQty())).sum();
        long grantedPcs = grants.stream()
                .filter(g -> "GRANTED".equals(g.getStatus()))
                .mapToLong(g -> g.getGrantCount() == null ? 0 : g.getGrantCount())
                .sum();
        long grantBatches = grants.stream().filter(g -> "GRANTED".equals(g.getStatus())).count();

        List<Map<String, Object>> rows = coupons.stream().map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("couponId", c.getCouponId());
            row.put("couponName", c.getCouponName());
            row.put("couponType", c.getCouponType());
            row.put("status", c.getStatus());
            row.put("totalQty", nz(c.getTotalQty()));
            row.put("issuedQty", nz(c.getIssuedQty()));
            row.put("usedQty", nz(c.getUsedQty()));
            row.put("writeoffRate", rate(nz(c.getUsedQty()), nz(c.getIssuedQty())));
            row.put("campaignId", c.getCampaignId());
            return row;
        }).toList();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("couponKinds", coupons.size());
        stats.put("totalStock", totalStock);
        stats.put("totalIssued", totalIssued);
        stats.put("totalUsed", totalUsed);
        stats.put("writeoffRate", rate(totalUsed, totalIssued));
        stats.put("grantBatches", grantBatches);
        stats.put("grantedPcs", grantedPcs);
        stats.put("rows", rows);
        return stats;
    }

    private Map<String, Object> campaignStats(List<Campaign> campaigns) {
        long totalSpent = campaigns.stream().mapToLong(c -> nz(c.getSpent())).sum();
        long totalActual = campaigns.stream().mapToLong(c -> nz(c.getActualAmount())).sum();
        long totalBudget = campaigns.stream().mapToLong(c -> nz(c.getBudget())).sum();
        long totalTarget = campaigns.stream().mapToLong(c -> nz(c.getTargetAmount())).sum();
        int totalNewCustomers = campaigns.stream().mapToInt(c -> c.getNewCustomers() == null ? 0 : c.getNewCustomers()).sum();
        long running = campaigns.stream().filter(c -> "RUNNING".equals(c.getStatus())).count();

        List<Map<String, Object>> rows = campaigns.stream().map(c -> {
            long spent = nz(c.getSpent());
            long actual = nz(c.getActualAmount());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("campaignId", c.getCampaignId());
            row.put("campaignName", c.getCampaignName());
            row.put("campaignType", c.getCampaignType());
            row.put("status", c.getStatus());
            row.put("spent", spent);
            row.put("actualAmount", actual);
            row.put("budget", nz(c.getBudget()));
            row.put("targetAmount", nz(c.getTargetAmount()));
            row.put("newCustomers", c.getNewCustomers() == null ? 0 : c.getNewCustomers());
            row.put("roi", roi(actual, spent));
            return row;
        }).toList();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("campaignCount", campaigns.size());
        stats.put("runningCount", running);
        stats.put("totalSpent", totalSpent);
        stats.put("totalActualAmount", totalActual);
        stats.put("totalBudget", totalBudget);
        stats.put("totalTargetAmount", totalTarget);
        stats.put("totalNewCustomers", totalNewCustomers);
        stats.put("overallRoi", roi(totalActual, totalSpent));
        stats.put("achieveRate", rate(totalActual, totalTarget));
        stats.put("rows", rows);
        return stats;
    }

    /** 比率（0~1）：分母为 0 返回 0，空态安全。 */
    private static double rate(long part, long total) {
        return total <= 0 ? 0d : Math.round(part * 10000.0 / total) / 10000.0;
    }

    /** ROI = 成交额 / 投放额，保留两位；投放为 0 返回 0。 */
    private static double roi(long actual, long spent) {
        return spent <= 0 ? 0d : Math.round(actual * 100.0 / spent) / 100.0;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
