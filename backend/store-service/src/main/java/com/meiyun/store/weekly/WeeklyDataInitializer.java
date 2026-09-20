package com.meiyun.store.weekly;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Component
@Order(73)
public class WeeklyDataInitializer implements ApplicationRunner {

    private static final String STORE_CODE = "SST01";

    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final WeeklyReportRepository repo;
    private final WeeklyService service;
    private final String datasourceUrl;

    public WeeklyDataInitializer(WeeklyReportRepository repo,
                                 WeeklyService service,
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

        service.seed(STORE_CODE, new WeeklyService.SeedReport(
                "2026-W34", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23),
                486300, 452800, 318, 96, 42, 58,
                "超声炮项目周销冠，林微咨询师个人业绩突破 24 万；新客转化率 42%，环比提升 6 个百分点。",
                "周三 A03 空调故障影响 2 单体验；玻尿酸库存预警，需补货。",
                "启动七夕会员专属活动；安排 A03 空调检修；完成 8 月存量客户回访。",
                "DRAFT", null, null));

        service.seed(STORE_CODE, new WeeklyService.SeedReport(
                "2026-W33", LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16),
                452800, 431500, 296, 88, 35, 55,
                "热玛吉套餐连带销售 12 单；老客复购集中在水光和皮肤管理。",
                "客流集中在周末，工作日预约不饱和；陈珂医生休 2 天。",
                "工作日 14:00-17:00 推出限时体验价；优化咨询师排班。",
                "SUBMITTED", "苏晴", OffsetDateTime.of(2026, 8, 17, 10, 12, 0, 0,
                        BIZ_ZONE.getRules().getOffset(LocalDate.of(2026, 8, 17).atStartOfDay()))));

        service.seed(STORE_CODE, new WeeklyService.SeedReport(
                "2026-W32", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9),
                431500, 398600, 284, 82, 31, 53,
                "会员日活动带来 28 万营收；私域社群新增 120 人。",
                "新客首单转化率偏低（28%），需加强咨询师话术培训。",
                "组织一次咨询话术内训；准备七夕活动物料。",
                "SUBMITTED", "苏晴", OffsetDateTime.of(2026, 8, 10, 9, 30, 0, 0,
                        BIZ_ZONE.getRules().getOffset(LocalDate.of(2026, 8, 10).atStartOfDay()))));

        service.seed(STORE_CODE, new WeeklyService.SeedReport(
                "2026-W31", LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 2),
                398600, 412000, 268, 76, 28, 51,
                "皮肤管理次卡销售良好；疗程卡续卡率 62%。",
                "营收环比下滑 3.3%，主要因高温天气客流减少。",
                "推夏季补水套餐；线上投放增加。",
                "SUBMITTED", "苏晴", OffsetDateTime.of(2026, 8, 3, 11, 5, 0, 0,
                        BIZ_ZONE.getRules().getOffset(LocalDate.of(2026, 8, 3).atStartOfDay()))));

        service.seed(STORE_CODE, new WeeklyService.SeedReport(
                "2026-W30", LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 26),
                412000, 389400, 275, 80, 33, 54,
                "抗衰类项目占比提升至 45%；客单价同比提升 8%。",
                "前台交接班出现一次预约信息遗漏。",
                "强化交接班 checklist；推抗衰组合套餐。",
                "SUBMITTED", "苏晴", OffsetDateTime.of(2026, 7, 27, 9, 50, 0, 0,
                        BIZ_ZONE.getRules().getOffset(LocalDate.of(2026, 7, 27).atStartOfDay()))));

        service.seed(STORE_CODE, new WeeklyService.SeedReport(
                "2026-W29", LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19),
                389400, 372000, 260, 74, 26, 52,
                "店庆预热活动启动；老客带新客 12 人。",
                "员工排班与客流高峰不匹配。",
                "调整周末排班；上线店庆主视觉。",
                "SUBMITTED", "苏晴", OffsetDateTime.of(2026, 7, 20, 10, 0, 0, 0,
                        BIZ_ZONE.getRules().getOffset(LocalDate.of(2026, 7, 20).atStartOfDay()))));
    }
}
