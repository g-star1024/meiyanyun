package com.meiyun.marketing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * M5-06 统计聚合单测：空态全 0、发券/核销/批次汇总、活动 ROI/达成率、FAILED 批次不计入。
 */
@ExtendWith(MockitoExtension.class)
class MarketingStatsServiceTest {

    @Mock
    CouponTemplateRepository couponRepo;
    @Mock
    CouponGrantRepository grantRepo;
    @Mock
    CampaignRepository campaignRepo;
    @Mock
    PushRecordRepository pushRepo;
    @Mock
    PosterRecordRepository posterRepo;
    @Mock
    LiveSessionRepository liveRepo;
    @Mock
    ShortVideoRepository videoRepo;

    MarketingStatsService service;

    @BeforeEach
    void setUp() {
        // 推送/海报/直播/短视频四个仓储 Mockito 默认返回空 List，
        // 全域看板新增的 push/funnel/channel/trend 四块在既有用例下自然回落 0/空
        service = new MarketingStatsService(couponRepo, grantRepo, campaignRepo,
                pushRepo, posterRepo, liveRepo, videoRepo);
    }

    @SuppressWarnings("unchecked")
    @Test
    void overview_empty_tables_returns_all_zeros_and_empty_rows() {
        when(couponRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(grantRepo.findAllByOrderByGrantedAtDesc()).thenReturn(List.of());
        when(campaignRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        Map<String, Object> resp = service.overview();
        Map<String, Object> coupon = (Map<String, Object>) resp.get("coupon");
        Map<String, Object> campaign = (Map<String, Object>) resp.get("campaign");

        assertEquals(0, coupon.get("couponKinds"));
        assertEquals(0, coupon.get("totalIssued"));
        assertEquals(0, coupon.get("totalUsed"));
        assertEquals(0.0, coupon.get("writeoffRate"));
        assertEquals(0L, coupon.get("grantedPcs"));
        assertTrue(((List<?>) coupon.get("rows")).isEmpty());

        assertEquals(0, campaign.get("campaignCount"));
        assertEquals(0L, campaign.get("totalSpent"));
        assertEquals(0L, campaign.get("totalActualAmount"));
        assertEquals(0.0, campaign.get("overallRoi"));
        assertTrue(((List<?>) campaign.get("rows")).isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void coupon_stats_aggregates_issued_used_and_granted_pcs_excluding_failed() {
        CouponTemplate c1 = coupon("CPN1", "ACTIVE", 100, 60, 15);
        CouponTemplate c2 = coupon("CPN2", "DISABLED", 50, 50, 0);
        when(couponRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(c1, c2));
        when(grantRepo.findAllByOrderByGrantedAtDesc()).thenReturn(List.of(
                grant("GR1", "GRANTED", 60),
                grant("GR2", "GRANTED", 50),
                grant("GR3", "FAILED", 999)));
        when(campaignRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        Map<String, Object> resp = service.overview();
        Map<String, Object> coupon = (Map<String, Object>) resp.get("coupon");

        assertEquals(2, coupon.get("couponKinds"));
        assertEquals(150, coupon.get("totalStock"));
        assertEquals(110, coupon.get("totalIssued"));
        assertEquals(15, coupon.get("totalUsed"));
        // 核销率 = 15/110 ≈ 0.1364
        assertEquals(0.1364, (Double) coupon.get("writeoffRate"), 0.0001);
        // FAILED 批次不计：60 + 50 = 110
        assertEquals(110L, coupon.get("grantedPcs"));
        assertEquals(2L, coupon.get("grantBatches"));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) coupon.get("rows");
        assertEquals(2, rows.size());
        assertEquals(0.25, (Double) rows.get(0).get("writeoffRate"), 0.0001);
    }

    @SuppressWarnings("unchecked")
    @Test
    void campaign_stats_computes_roi_and_achieve_rate_and_new_customers() {
        Campaign a1 = campaign("CP1", "RUNNING", 200000L, 600000L, 500000L, 30);
        Campaign a2 = campaign("CP2", "ENDED", 0L, 0L, 100000L, 5);
        when(couponRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(grantRepo.findAllByOrderByGrantedAtDesc()).thenReturn(List.of());
        when(campaignRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(a1, a2));

        Map<String, Object> resp = service.overview();
        Map<String, Object> campaign = (Map<String, Object>) resp.get("campaign");

        assertEquals(2, campaign.get("campaignCount"));
        assertEquals(1L, campaign.get("runningCount"));
        assertEquals(200000L, campaign.get("totalSpent"));
        assertEquals(600000L, campaign.get("totalActualAmount"));
        assertEquals(35, campaign.get("totalNewCustomers"));
        // ROI = 600000 / 200000 = 3.0
        assertEquals(3.0, (Double) campaign.get("overallRoi"), 0.001);
        // 达成率 = 600000 / 600000 = 1.0
        assertEquals(1.0, (Double) campaign.get("achieveRate"), 0.001);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) campaign.get("rows");
        assertEquals(3.0, (Double) rows.get(0).get("roi"), 0.001);
        // 投放为 0 的活动 ROI 安全返回 0（不除零）
        assertEquals(0.0, (Double) rows.get(1).get("roi"), 0.001);
    }

    private CouponTemplate coupon(String id, String status, int total, int issued, int used) {
        CouponTemplate c = new CouponTemplate();
        c.setCouponId(id);
        c.setCouponName("测试券");
        c.setCouponType("AMOUNT");
        c.setFaceValue(5000L);
        c.setThreshold(0L);
        c.setTotalQty(total);
        c.setIssuedQty(issued);
        c.setUsedQty(used);
        c.setStatus(status);
        c.setGrantScope("ALL");
        c.setValidStart(LocalDate.now().minusDays(1));
        c.setValidEnd(LocalDate.now().plusDays(30));
        c.setCreatedAt(OffsetDateTime.now());
        return c;
    }

    private CouponGrant grant(String id, String status, int count) {
        CouponGrant g = new CouponGrant();
        g.setGrantId(id);
        g.setCouponId("CPN1");
        g.setCouponName("测试券");
        g.setGrantScope("ALL");
        g.setTargetName("全部客户");
        g.setGrantCount(count);
        g.setStatus(status);
        g.setGrantedAt(OffsetDateTime.now());
        g.setOperator("SE101");
        return g;
    }

    private Campaign campaign(String id, String status, long spent, long actual, long target, int newCust) {
        Campaign c = new Campaign();
        c.setCampaignId(id);
        c.setCampaignName("测试活动");
        c.setCampaignType("FULL_REDUCE");
        c.setStatus(status);
        c.setChannels("[\"抖音\"]");
        c.setBudget(1000000L);
        c.setSpent(spent);
        c.setTargetAmount(target);
        c.setActualAmount(actual);
        c.setNewCustomers(newCust);
        c.setStoreScope("全部门店");
        c.setOwner("SE101");
        c.setCreatedAt(OffsetDateTime.now());
        return c;
    }
}
