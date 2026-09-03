package com.meiyun.marketing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * M5-14 营销看板统计聚合（只读）。
 *
 * 数据口径（营销库内真实数据，金额 bigint 存「分」，前端适配层转元）：
 * - 发券：coupon_template.issuedQty 累计已发放、usedQty 累计已核销；
 *   coupon_grant.grantCount（status=GRANTED）为发放批次实际发放张数，二者互为印证。
 * - 活动转化：campaign.spent 投放额、actualAmount 成交额、newCustomers 新客；
 *   综合 ROI = 总成交额 / 总投放额（投放为 0 时 ROI 为 0，避免除零）。
 * - 推送：push_record 仅记录「成功发送」的客户级触达（发送数即近似到达，无运营商回执字段），
 *   按 distinct content 近似批次数；推送渠道为旧码 短信/小程序/App。
 * - 内容链路：裂变海报（share→scan→lead→visit→deal 五级漏斗）、直播（viewers/linkClicks/dealCount）、
 *   短视频（plays/likes/dealCount）。内容曝光/互动/成交即看板「推送效果/漏斗/渠道/趋势」的真实来源——
 *   push_record 无到达/点击/转化回执字段，全域效果用内容链路真实数据近似，不造数。
 * - 渠道：内容按平台映射（DOUYIN→抖音、WECHAT_CHANNEL→视频号、XIAOHONGSHU→小红书、海报私域裂变→微信私域）；
 *   投放额取 campaign.spent 按活动渠道数均摊（活动不记录分渠道花费）；ROI = 内容成交额 / 分摊投放额。
 * - 漏斗按「到店核销」O2O 链路严格递减建模：曝光→领券→到店（海报到访+券核销）→成交（海报成交+券核销）；
 *   直播/短视频的线上团购成交不经过店环节，不计入漏斗成交（其成交体现在 KPI 转化率/渠道排行）。
 */
@Service
public class MarketingStatsService {

    private static final List<String> CHANNEL_ORDER =
            List.of("抖音", "小红书", "视频号", "微信私域", "美团", "大众点评");

    private final CouponTemplateRepository couponRepo;
    private final CouponGrantRepository grantRepo;
    private final CampaignRepository campaignRepo;
    private final PushRecordRepository pushRepo;
    private final PosterRecordRepository posterRepo;
    private final LiveSessionRepository liveRepo;
    private final ShortVideoRepository videoRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MarketingStatsService(CouponTemplateRepository couponRepo,
                                 CouponGrantRepository grantRepo,
                                 CampaignRepository campaignRepo,
                                 PushRecordRepository pushRepo,
                                 PosterRecordRepository posterRepo,
                                 LiveSessionRepository liveRepo,
                                 ShortVideoRepository videoRepo) {
        this.couponRepo = couponRepo;
        this.grantRepo = grantRepo;
        this.campaignRepo = campaignRepo;
        this.pushRepo = pushRepo;
        this.posterRepo = posterRepo;
        this.liveRepo = liveRepo;
        this.videoRepo = videoRepo;
    }

    public Map<String, Object> overview() {
        List<CouponTemplate> coupons = couponRepo.findAllByOrderByCreatedAtDesc();
        List<CouponGrant> grants = grantRepo.findAllByOrderByGrantedAtDesc();
        List<Campaign> campaigns = campaignRepo.findAllByOrderByCreatedAtDesc();
        List<PushRecord> pushes = pushRepo.findAll();
        List<PosterRecord> posters = posterRepo.findAllByOrderByCreatedAtDesc();
        List<LiveSession> lives = liveRepo.findAllByOrderByStartTimeDesc();
        List<ShortVideo> videos = videoRepo.findAllByOrderByPublishedAtDesc();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("coupon", couponStats(coupons, grants));
        resp.put("campaign", campaignStats(campaigns));
        resp.put("push", pushStats(pushes, posters, lives, videos));
        resp.put("funnel", funnelStats(coupons, pushes, posters, lives, videos));
        resp.put("channel", channelStats(campaigns, posters, lives, videos));
        resp.put("trend", trendStats(pushes, posters, lives, videos));
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

    /**
     * 推送/全域触达效果：sent=distinct content 近似批次；delivered=推送发送数+内容总曝光（全域触达）；
     * clicked=海报扫码+直播商品点击+短视频点赞（内容互动近似点击，推送无点击回执）；
     * converted=内容成交单量；ctr/cvr 为百分比（保留 1 位小数）。
     */
    private Map<String, Object> pushStats(List<PushRecord> pushes, List<PosterRecord> posters,
                                          List<LiveSession> lives, List<ShortVideo> videos) {
        long sent = pushes.stream().map(PushRecord::getContent).filter(Objects::nonNull).distinct().count();
        long contentExposure = contentExposure(posters, lives, videos);
        long delivered = pushes.size() + contentExposure;
        long clicked = posters.stream().mapToLong(p -> nz(p.getScan())).sum()
                + lives.stream().mapToLong(l -> nz(l.getLinkClicks())).sum()
                + videos.stream().mapToLong(v -> nz(v.getLikes())).sum();
        long converted = contentDeals(posters, lives, videos);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("sent", sent);
        stats.put("delivered", delivered);
        stats.put("clicked", clicked);
        stats.put("converted", converted);
        stats.put("ctr", percent(clicked, delivered));
        stats.put("cvr", percent(converted, clicked));
        return stats;
    }

    /**
     * 转化漏斗（到店核销 O2O 链路，严格递减）：
     * 曝光=推送+海报分享+直播观看+短视频播放；领券=券模板累计发放；
     * 到店=海报到访 visit+券核销 used；成交=海报成交 deal+券核销 used。
     * ratio 为相对上一级的百分比（首段 100）。线上团购成交见类注释。
     */
    private Map<String, Object> funnelStats(List<CouponTemplate> coupons, List<PushRecord> pushes,
                                            List<PosterRecord> posters, List<LiveSession> lives,
                                            List<ShortVideo> videos) {
        long exposure = pushes.size() + contentExposure(posters, lives, videos);
        long couponReceived = coupons.stream().mapToLong(c -> nz(c.getIssuedQty())).sum();
        long arrival = posters.stream().mapToLong(p -> nz(p.getVisit())).sum()
                + coupons.stream().mapToLong(c -> nz(c.getUsedQty())).sum();
        long deal = posters.stream().mapToLong(p -> nz(p.getDeal())).sum()
                + coupons.stream().mapToLong(c -> nz(c.getUsedQty())).sum();

        long[] values = {exposure, couponReceived, arrival, deal};
        String[] keys = {"exposure", "coupon", "arrival", "deal"};
        String[] labels = {"曝光", "领券", "到店", "成交"};
        List<Map<String, Object>> stages = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("key", keys[i]);
            s.put("label", labels[i]);
            s.put("value", values[i]);
            s.put("ratio", i == 0 ? 100d : percent(values[i], values[i - 1]));
            stages.add(s);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("stages", stages);
        return stats;
    }

    /**
     * 渠道业绩排行：营收/成交/线索取自内容链路（海报=微信私域，平台映射见类注释），
     * 投放额取 campaign.spent 按活动渠道数均摊；按营收降序输出，ROI=营收/投放（倍数两位）。
     * 线索口径：直播=商品点击 linkClicks、短视频=点赞 likes、海报=留资 lead。
     */
    private Map<String, Object> channelStats(List<Campaign> campaigns, List<PosterRecord> posters,
                                             List<LiveSession> lives, List<ShortVideo> videos) {
        Map<String, long[]> agg = new HashMap<>();
        for (String ch : CHANNEL_ORDER) {
            agg.put(ch, new long[4]); // [revenue分, spent分, deals, leads]
        }

        posters.forEach(p -> {
            long[] a = agg.get("微信私域");
            a[0] += nz(p.getDealAmount());
            a[2] += nz(p.getDeal());
            a[3] += nz(p.getLead());
        });
        lives.forEach(l -> {
            long[] a = agg.get(platformChannel(l.getPlatform()));
            if (a == null) return;
            a[0] += nz(l.getDealAmount());
            a[2] += nz(l.getDealCount());
            a[3] += nz(l.getLinkClicks());
        });
        videos.forEach(v -> {
            long[] a = agg.get(platformChannel(v.getPlatform()));
            if (a == null) return;
            a[0] += nz(v.getDealAmount());
            a[2] += nz(v.getDealCount());
            a[3] += nz(v.getLikes());
        });
        campaigns.forEach(c -> {
            List<String> chs = parseChannels(c.getChannels());
            if (chs.isEmpty()) return;
            long spentEach = nz(c.getSpent()) / chs.size();
            for (String ch : chs) {
                long[] a = agg.get(ch);
                if (a != null) a[1] += spentEach;
            }
        });

        List<Map<String, Object>> rows = new ArrayList<>();
        agg.forEach((name, a) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", name);
            row.put("name", name);
            row.put("revenue", a[0]);
            row.put("spent", a[1]);
            row.put("deals", a[2]);
            row.put("leads", a[3]);
            row.put("roi", roi(a[0], a[1]));
            rows.add(row);
        });
        rows.sort(Comparator.comparingLong(r -> -((long) r.get("revenue"))));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("rows", rows);
        return stats;
    }

    /** 近 6 月触达/成交趋势：触达=推送发送+海报分享+直播观看+短视频播放；成交=内容链路成交单量。 */
    private Map<String, Object> trendStats(List<PushRecord> pushes, List<PosterRecord> posters,
                                           List<LiveSession> lives, List<ShortVideo> videos) {
        YearMonth thisMonth = YearMonth.now();
        Map<YearMonth, long[]> byMonth = new HashMap<>();
        for (int i = 5; i >= 0; i--) {
            byMonth.put(thisMonth.minusMonths(i), new long[2]);
        }

        pushes.forEach(p -> {
            if (p.getSentAt() != null) {
                long[] a = byMonth.get(YearMonth.from(p.getSentAt()));
                if (a != null) a[0] += 1;
            }
        });
        posters.forEach(p -> {
            if (p.getCreatedAt() != null) {
                long[] a = byMonth.get(YearMonth.from(p.getCreatedAt()));
                if (a != null) {
                    a[0] += nz(p.getShare());
                    a[1] += nz(p.getDeal());
                }
            }
        });
        lives.forEach(l -> {
            if (l.getStartTime() != null) {
                long[] a = byMonth.get(YearMonth.from(l.getStartTime()));
                if (a != null) {
                    a[0] += nz(l.getViewers());
                    a[1] += nz(l.getDealCount());
                }
            }
        });
        videos.forEach(v -> {
            if (v.getPublishedAt() != null) {
                long[] a = byMonth.get(YearMonth.from(v.getPublishedAt()));
                if (a != null) {
                    a[0] += nz(v.getPlays());
                    a[1] += nz(v.getDealCount());
                }
            }
        });

        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth ym = thisMonth.minusMonths(i);
            long[] a = byMonth.get(ym);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("month", ym.toString());
            point.put("reach", a[0]);
            point.put("converted", a[1]);
            points.add(point);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("points", points);
        return stats;
    }

    /** 内容总曝光：海报分享 + 直播观看 + 短视频播放。 */
    private long contentExposure(List<PosterRecord> posters, List<LiveSession> lives, List<ShortVideo> videos) {
        return posters.stream().mapToLong(p -> nz(p.getShare())).sum()
                + lives.stream().mapToLong(l -> nz(l.getViewers())).sum()
                + videos.stream().mapToLong(v -> nz(v.getPlays())).sum();
    }

    /** 内容总成交单量：海报成交 + 直播成交 + 短视频成交。 */
    private long contentDeals(List<PosterRecord> posters, List<LiveSession> lives, List<ShortVideo> videos) {
        return posters.stream().mapToLong(p -> nz(p.getDeal())).sum()
                + lives.stream().mapToLong(l -> nz(l.getDealCount())).sum()
                + videos.stream().mapToLong(v -> nz(v.getDealCount())).sum();
    }

    /** 内容平台码 → 渠道中文名（与 m1Marketing.CHANNELS 一致）。 */
    private String platformChannel(String platform) {
        if (platform == null) return null;
        return switch (platform) {
            case "DOUYIN" -> "抖音";
            case "WECHAT_CHANNEL" -> "视频号";
            case "XIAOHONGSHU" -> "小红书";
            default -> null;
        };
    }

    /** 活动渠道 JSON 数组文本解析，失败回落空列表。 */
    private List<String> parseChannels(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 比率（0~1）：分母为 0 返回 0，空态安全。 */
    private static double rate(long part, long total) {
        return total <= 0 ? 0d : Math.round(part * 10000.0 / total) / 10000.0;
    }

    /** 百分比（保留 1 位小数）：分母为 0 返回 0。 */
    private static double percent(long part, long total) {
        return total <= 0 ? 0d : Math.round(part * 1000.0 / total) / 10.0;
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
