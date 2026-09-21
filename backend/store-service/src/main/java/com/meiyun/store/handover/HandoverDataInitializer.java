package com.meiyun.store.handover;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(72)
public class HandoverDataInitializer implements ApplicationRunner {

    private static final String STORE_CODE = "SST01";
    private static final String MANAGER = "许店长";
    private static final String CONSULTANT = "林咨询";
    private static final String THERAPIST = "苏治疗";

    private final HandoverRepository hoRepo;
    private final HandoverService service;
    private final String datasourceUrl;

    public HandoverDataInitializer(HandoverRepository hoRepo,
                                   HandoverService service,
                                   @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.hoRepo = hoRepo;
        this.service = service;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) return;
        if (hoRepo.count() > 0) return;

        LocalDate today = HandoverService.today();
        LocalDate yesterday = today.minusDays(1);

        seedTodaySubmitted(today);
        seedYesterdayEvening(yesterday);
        seedYesterdayFull(yesterday);
    }

    private void seedTodaySubmitted(LocalDate date) {
        List<HandoverService.SeedTodo> todos = List.of(
                new HandoverService.SeedTodo("刘女士 14:00 玻尿酸复诊，提前备好病历", "CUSTOMER", false, true),
                new HandoverService.SeedTodo("A 区玻尿酸精华液库存不足，晚班补货 5 盒", "TASK", false, false),
                new HandoverService.SeedTodo("3 号治疗室空调异响，已报修待工单跟进", "ISSUE", false, true)
        );

        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(timelineEntry("创建交接班草稿", CONSULTANT, at(date, 11, 45)));
        timeline.add(timelineEntry("提交交接班", CONSULTANT, at(date, 12, 5)));

        service.seed(new HandoverService.SeedHandover(hoNo(date, 1), STORE_CODE, "MORNING", date,
                HandoverService.SUBMITTED, CONSULTANT, MANAGER,
                2860000L, 12, 18,
                "上午团购到店客户较多，刘女士复诊满意度高，有升单意向，建议晚班咨询师跟进。",
                "现金收款 2 笔共 3,600 元已入保险柜，扫码支付 10 笔，账目已与系统核对一致。",
                "3 号治疗室空调异响，已报修；其余设备运行正常。",
                null, at(date, 12, 5), null, at(date, 11, 45), timeline, todos));
    }

    private void seedYesterdayEvening(LocalDate date) {
        List<HandoverService.SeedTodo> todos = List.of(
                new HandoverService.SeedTodo("回访赵女士热玛吉术后反应", "CUSTOMER", true, false),
                new HandoverService.SeedTodo("盘点前台耗材并登记", "TASK", true, false)
        );

        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(timelineEntry("创建交接班草稿", MANAGER, at(date, 17, 40)));
        timeline.add(timelineEntry("提交交接班", MANAGER, at(date, 18, 2)));
        timeline.add(timelineEntry("确认接收", CONSULTANT, at(date, 18, 10)));

        service.seed(new HandoverService.SeedHandover(hoNo(date, 1), STORE_CODE, "EVENING", date,
                HandoverService.CONFIRMED, MANAGER, CONSULTANT,
                3520000L, 15, 22,
                "晚高峰客流集中，赵女士术后轻微红肿属正常反应，已交待注意事项。",
                "全日现金 5,200 元已清点入柜，与日报一致。",
                "设备全部正常运行。",
                "已知晓赵女士回访事项，明早优先处理。",
                at(date, 18, 2), at(date, 18, 10), at(date, 17, 40), timeline, todos));
    }

    private void seedYesterdayFull(LocalDate date) {
        List<HandoverService.SeedTodo> todos = List.of(
                new HandoverService.SeedTodo("确认明日 3 台手术术前准备", "TASK", true, false),
                new HandoverService.SeedTodo("跟进孙女士疗程卡续费意向", "CUSTOMER", true, false)
        );

        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(timelineEntry("创建交接班草稿", THERAPIST, at(date, 20, 30)));
        timeline.add(timelineEntry("提交交接班", THERAPIST, at(date, 21, 0)));
        timeline.add(timelineEntry("确认接收", MANAGER, at(date, 21, 20)));

        service.seed(new HandoverService.SeedHandover(hoNo(date, 2), STORE_CODE, "FULL", date,
                HandoverService.CONFIRMED, THERAPIST, MANAGER,
                5180000L, 21, 30,
                "全日运营平稳，孙女士疗程卡余额不足，有续费意向，报价单已发。",
                "现金与扫码账目均已核对，无差异。",
                "超声刀设备保养已完成，登记在册。",
                "收到，续费事项明日跟进。",
                at(date, 21, 0), at(date, 21, 20), at(date, 20, 30), timeline, todos));
    }

    private String hoNo(LocalDate date, int seq) {
        return "HJ-" + date.toString().replace("-", "") + "-" + String.format("%06d", seq);
    }

    private OffsetDateTime at(LocalDate date, int hour, int minute) {
        return OffsetDateTime.of(date, LocalTime.of(hour, minute), ZoneOffset.ofHours(8));
    }

    private Map<String, Object> timelineEntry(String action, String by, OffsetDateTime at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("by", by);
        m.put("at", at);
        return m;
    }
}
