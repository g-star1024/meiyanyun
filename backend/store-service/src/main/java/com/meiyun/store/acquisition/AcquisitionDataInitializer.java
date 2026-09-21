package com.meiyun.store.acquisition;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@Component
@Order(75)
public class AcquisitionDataInitializer implements ApplicationRunner {

    private static final String STORE_CODE = "SST01";

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AcquisitionRepository repo;
    private final AcquisitionService service;
    private final String datasourceUrl;

    public AcquisitionDataInitializer(AcquisitionRepository repo,
                                      AcquisitionService service,
                                      @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.repo = repo;
        this.service = service;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) return;
        if (repo.count() > 0) return;

        OffsetDateTime now = OffsetDateTime.now(AcquisitionService.BIZ_ZONE);

        AcquisitionService.SeedCampaign[] seeds = {
            new AcquisitionService.SeedCampaign(
                    "99元水光体验日", "TRIAL", 12800, 186, 72, 30000, 18600,
                    "ONGOING", 6, "白桥（运营）", "小红书+私域"),
            new AcquisitionService.SeedCampaign(
                    "闺蜜拼团·热玛吉双人8折", "GROUP", 8600, 124, 58, 50000, 32000,
                    "ONGOING", 12, "吴桐（运营）", "微信社群"),
            new AcquisitionService.SeedCampaign(
                    "老带新·赠光子嫩肤1次", "REFERRAL", 5200, 98, 61, 20000, 15200,
                    "ONGOING", 20, "陈雅琳（店长）", "老客企微"),
            new AcquisitionService.SeedCampaign(
                    "19.9元皮肤检测体验", "TRIAL", 22000, 342, 88, 25000, 25000,
                    "ENDED", 45, "白桥（运营）", "抖音本地推"),
            new AcquisitionService.SeedCampaign(
                    "三人拼团·童颜针体验", "GROUP", 6800, 76, 30, 40000, 38500,
                    "ENDED", 60, "吴桐（运营）", "美团点评"),
            new AcquisitionService.SeedCampaign(
                    "双11老客回馈拼团", "REFERRAL", 9400, 156, 92, 35000, 34800,
                    "ENDED", 80, "陈雅琳（店长）", "全渠道"),
            new AcquisitionService.SeedCampaign(
                    "新年焕颜体验价（策划中）", "TRIAL", 0, 0, 0, 45000, 0,
                    "DRAFT", 0, "白桥（运营）", "待定"),
        };

        for (int i = 0; i < seeds.length; i++) {
            AcquisitionService.SeedCampaign c = seeds[i];
            String day = now.minusDays(c.startAgo()).format(DAY_FMT);
            String aqNo = String.format("AQ-%s-%06d", day, i + 1);
            service.seed(STORE_CODE, aqNo, c);
        }
    }
}
