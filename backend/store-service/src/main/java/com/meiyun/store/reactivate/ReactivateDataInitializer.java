package com.meiyun.store.reactivate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@Component
@Order(76)
public class ReactivateDataInitializer implements ApplicationRunner {

    private static final String STORE_CODE = "SST01";

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReactivateRepository repo;
    private final ReactivateService service;
    private final String datasourceUrl;

    public ReactivateDataInitializer(ReactivateRepository repo,
                                     ReactivateService service,
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

        OffsetDateTime now = OffsetDateTime.now(ReactivateService.BIZ_ZONE);
        String today = now.format(DAY_FMT);

        ReactivateService.SeedCustomer[] seeds = {
            new ReactivateService.SeedCustomer(
                    "赵雨晴", "钻石", "138****2041", 112, 18600, "RECOVERED",
                    "林微", "PHONE", now.minusDays(110), null, now.minusHours(4),
                    "客户已到店做热玛吉，充值 10000"),
            new ReactivateService.SeedCustomer(
                    "孙佳宁", "金卡", "139****6612", 95, 8200, "ASSIGNED",
                    "林微", "WECHAT", now.minusDays(93), now.plusDays(2), null, null),
            new ReactivateService.SeedCustomer(
                    "王晓明", "银卡", "136****3018", 78, 3600, "PENDING",
                    null, null, null, null, null, null),
            new ReactivateService.SeedCustomer(
                    "陈美玲", "金卡", "135****7788", 62, 6400, "VISITED",
                    "白桥", "WECHAT", now.minusDays(60), null, now.minusHours(22),
                    "客户反馈近期出差，预计月底回店"),
            new ReactivateService.SeedCustomer(
                    "李思琪", "钻石", "137****9150", 45, 24300, "ASSIGNED",
                    "林微", "PHONE", now.minusDays(43), now.plusDays(5), null, null),
            new ReactivateService.SeedCustomer(
                    "周心怡", "银卡", "131****2204", 38, 1200, "PENDING",
                    null, null, null, null, null, null),
            new ReactivateService.SeedCustomer(
                    "吴雅琴", "普通", "186****5509", 35, 0, "VISITED",
                    "白桥", "SMS", now.minusDays(33), null, now.minusHours(40),
                    "客户反馈近期出差，预计月底回店"),
            new ReactivateService.SeedCustomer(
                    "郑雪", "金卡", "133****8817", 41, 5200, "PENDING",
                    null, null, null, null, null, null),
        };

        for (int i = 0; i < seeds.length; i++) {
            String rcNo = String.format("RC-%s-%06d", today, i + 1);
            service.seed(STORE_CODE, rcNo, seeds[i]);
        }
    }
}
