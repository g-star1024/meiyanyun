package com.meiyun.store.inspection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

@Component
@Order(74)
public class InspectionDataInitializer implements ApplicationRunner {

    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final InspectionRepository repo;
    private final InspectionService service;
    private final String datasourceUrl;

    public InspectionDataInitializer(InspectionRepository repo,
                                     InspectionService service,
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

        service.seed("SST01", new InspectionService.SeedInspection(
                "INS-20260919-000001", at(2026, 9, 19, 10), "ENV", "陈野", "PENDING",
                List.of(
                        item("大厅整洁度", 8, null),
                        item("治疗室消毒", 6, "A03 台面有污渍"),
                        item("卫生间物资", 5, "纸巾缺失"),
                        item("灯光空调", 9, null),
                        item("香氛氛围", 8, null)),
                List.of(
                        issue("治疗室消毒 未达标（6/10）：A03 台面有污渍", "李娜（前台主管）", "DOING",
                                at(2026, 9, 26, 10), true),
                        issue("卫生间物资 未达标（5/10）：纸巾缺失", "吴桐（运营）", "OPEN",
                                at(2026, 9, 26, 10), false)),
                at(2026, 9, 19, 10), null));

        service.seed("SST01", new InspectionService.SeedInspection(
                "INS-20260916-000002", at(2026, 9, 16, 10), "SERVICE", "苏晴", "IN_PROGRESS",
                List.of(
                        item("接待话术", 9, null),
                        item("术前告知", 7, null),
                        item("术后回访", 6, "24h 内回访率 82%"),
                        item("客诉响应", 8, null),
                        item("仪容仪表", 9, null)),
                List.of(
                        issue("术后回访 未达标（6/10）：24h 内回访率 82%", "李娜（前台主管）", "DOING",
                                at(2026, 9, 26, 10), true)),
                at(2026, 9, 16, 10), null));

        service.seed("SST01", new InspectionService.SeedInspection(
                "INS-20260909-000003", at(2026, 9, 9, 10), "COMPLIANCE", "周岚", "DONE",
                List.of(
                        item("医师资质公示", 10, null),
                        item("器械消毒记录", 9, null),
                        item("麻醉药品台账", 9, null),
                        item("知情同意书", 10, null),
                        item("消防设施", 9, null)),
                List.of(),
                at(2026, 9, 9, 10), at(2026, 9, 9, 16)));

        service.seed("SST02", new InspectionService.SeedInspection(
                "INS-20260903-000004", at(2026, 9, 3, 10), "ENV", "陈野", "DONE",
                List.of(
                        item("大厅整洁度", 9, null),
                        item("治疗室消毒", 9, null),
                        item("卫生间物资", 8, null),
                        item("灯光空调", 9, null),
                        item("香氛氛围", 8, null)),
                List.of(),
                at(2026, 9, 3, 10), at(2026, 9, 3, 16)));

        service.seed("SST03", new InspectionService.SeedInspection(
                "INS-20260830-000005", at(2026, 8, 30, 10), "SERVICE", "苏晴", "IN_PROGRESS",
                List.of(
                        item("接待话术", 7, null),
                        item("术前告知", 6, "部分项目未逐条告知"),
                        item("术后回访", 8, null),
                        item("客诉响应", 9, null),
                        item("仪容仪表", 8, null)),
                List.of(
                        issue("术前告知 未达标（6/10）：部分项目未逐条告知", "李娜（前台主管）", "DOING",
                                at(2026, 10, 3, 10), true)),
                at(2026, 8, 30, 10), null));

        service.seed("SST01", new InspectionService.SeedInspection(
                "INS-20260817-000006", at(2026, 8, 17, 10), "COMPLIANCE", "周岚", "DONE",
                List.of(
                        item("医师资质公示", 10, null),
                        item("器械消毒记录", 10, null),
                        item("麻醉药品台账", 8, "一项记录签名缺失"),
                        item("知情同意书", 10, null),
                        item("消防设施", 10, null)),
                List.of(
                        issue("麻醉药品台账 未达标（8/10）：一项记录签名缺失", "吴桐（运营）", "DONE",
                                at(2026, 8, 24, 10), true)),
                at(2026, 8, 17, 10), at(2026, 8, 22, 16)));
    }

    private static OffsetDateTime at(int y, int m, int d, int hh) {
        ZoneOffset off = BIZ_ZONE.getRules().getOffset(LocalDate.of(y, m, d).atStartOfDay());
        return OffsetDateTime.of(y, m, d, hh, 0, 0, 0, off);
    }

    private static InspectionService.SeedItem item(String name, int score, String note) {
        return new InspectionService.SeedItem(name, score, note);
    }

    private static InspectionService.SeedIssue issue(String desc, String owner, String status,
                                                     OffsetDateTime dueAt, boolean hasPhoto) {
        return new InspectionService.SeedIssue(desc, owner, status, dueAt, hasPhoto);
    }
}
