package com.meiyun.store.daily;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(71)
public class DailyDataInitializer implements ApplicationRunner {

    private static final String STORE_CODE = "SST01";
    private static final String MANAGER = "陈雅琳（店长）";

    private final DailyReportRepository reportRepo;
    private final DailyService service;
    private final String datasourceUrl;

    public DailyDataInitializer(DailyReportRepository reportRepo,
                                 DailyService service,
                                 @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.reportRepo = reportRepo;
        this.service = service;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) return;
        if (reportRepo.count() > 0) return;

        LocalDate yesterday = DailyService.today().minusDays(1);
        LocalDate today = DailyService.today();

        seedYesterday(yesterday);
        seedToday(today);
    }

    private void seedYesterday(LocalDate date) {
        List<Integer> hourly = List.of(6, 4, 9, 7, 11, 13, 10, 14, 18, 15, 9, 5);

        List<DailyService.SeedTodo> todos = List.of(
                new DailyService.SeedTodo("补货：玻尿酸精华液 5 盒", "TASK", true, false),
                new DailyService.SeedTodo("回访客户赵雨晴射频紧肤效果", "CUSTOMER", true, false),
                new DailyService.SeedTodo("报修超声刀治疗仪 E07 故障已转工单", "ISSUE", true, true)
        );

        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(timelineEntry("创建门店日报草稿", MANAGER,
                OffsetDateTime.of(date, java.time.LocalTime.of(21, 0), ZoneOffset.ofHours(8))));
        timeline.add(timelineEntry("提交门店日报", MANAGER,
                OffsetDateTime.of(date, java.time.LocalTime.of(21, 30), ZoneOffset.ofHours(8))));

        service.seed(new DailyService.SeedReport(STORE_CODE, date, DailyService.SUBMITTED,
                hourly.stream().mapToInt(Integer::intValue).sum(), 23, 18, 2,
                hourly,
                "超声刀治疗仪上午出现 E07 报错，已转服务工单并暂停使用。",
                "昨日整体平稳，晚高峰客流偏高，建议增加晚班咨询师排班。",
                MANAGER,
                OffsetDateTime.of(date, java.time.LocalTime.of(21, 30), ZoneOffset.ofHours(8)),
                timeline, todos));
    }

    private void seedToday(LocalDate date) {
        List<Integer> hourly = List.of(4, 3, 6, 5, 8, 7, 9, 11, 10, 12, 8, 0);

        List<DailyService.SeedTodo> todos = List.of(
                new DailyService.SeedTodo("跟进客户孙佳宁玻尿酸术后护理咨询", "CUSTOMER", false, false),
                new DailyService.SeedTodo("盘点 A 区耗材并补货", "TASK", false, false),
                new DailyService.SeedTodo("空调出风异味处理进度确认", "ISSUE", false, true)
        );

        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(timelineEntry("创建今日日报草稿", MANAGER, DailyService.now()));

        service.seed(new DailyService.SeedReport(STORE_CODE, date, DailyService.DRAFT,
                hourly.stream().mapToInt(Integer::intValue).sum(), 14, 11, 1,
                hourly, "", "", null, null, timeline, todos));
    }

    private Map<String, Object> timelineEntry(String action, String by, OffsetDateTime at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("by", by);
        m.put("at", at);
        return m;
    }
}
