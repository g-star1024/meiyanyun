package com.meiyun.store.workorder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
@Order(70)
public class WorkOrderDataInitializer implements ApplicationRunner {

    private static final List<String> ASSIGNEES =
            List.of("周敏（美容师）", "李娜（前台）", "吴桐（运营）", "陈雅琳（店长）");

    private final WorkOrderRepository woRepo;
    private final WorkOrderService service;
    private final String datasourceUrl;

    public WorkOrderDataInitializer(WorkOrderRepository woRepo,
                                    WorkOrderService service,
                                    @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.woRepo = woRepo;
        this.service = service;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) return;
        if (woRepo.count() > 0) return;

        seedOne(1, "REPAIR", "超声刀治疗仪报修", "设备开机后显示 E07 报错，无法进入治疗模式",
                "陈美玲", "超声刀治疗仪", "A03", "PENDING", "HIGH", 2, 1);
        seedOne(2, "CUSTOMER", "射频紧肤效果投诉", "客户赵雨晴反馈做完一次后无明显改善，要求重做",
                "赵雨晴", "射频紧肤", "B02", "IN_PROGRESS", "HIGH", -1, 3);
        seedOne(3, "INSPECTION", "每日设备巡检", "检查激光仪器冷却液水位及手柄消毒记录",
                null, "激光仪器", "A01", "DONE", "MEDIUM", -4, 5);
        seedOne(4, "CONSULT", "玻尿酸术后护理咨询", "客户孙佳宁咨询术后 24h 内能否化妆",
                "孙佳宁", "玻尿酸", "C01", "PENDING", "LOW", 4, 2);
        seedOne(5, "REPAIR", "空调出风异味处理", "候诊区空调出风有异味，需清洁滤网",
                null, "中央空调", "大厅", "IN_PROGRESS", "MEDIUM", 1, 4);
        seedOne(6, "CUSTOMER", "预约时间冲突协调", "客户王晓明预约的咨询师临时请假，需改约",
                "王晓明", "咨询预约", "前台", "DONE", "LOW", -6, 7);
    }

    private void seedOne(int seq, String type, String title, String description,
                         String customerName, String project, String room,
                         String status, String priority, int deadlineHours, int createdHoursAgo) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime createdAt = now.minusHours(createdHoursAgo);
        OffsetDateTime deadline = now.plusHours(deadlineHours);
        String day = createdAt.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String woNo = String.format("WO-%s-%06d", day, seq);
        String assignee = ASSIGNEES.get((seq - 1) % 4);

        OffsetDateTime startedAt = null;
        OffsetDateTime completedAt = null;
        List<WorkOrderService.SeedNote> notes = new ArrayList<>();
        notes.add(new WorkOrderService.SeedNote("系统", "自动创建", createdAt));
        if ("IN_PROGRESS".equals(status)) {
            startedAt = now.minusHours(seq);
        } else if ("DONE".equals(status)) {
            startedAt = now.minusHours(seq);
            completedAt = now.minusHours(seq - 1L);
            notes.add(new WorkOrderService.SeedNote(assignee, "已完成", completedAt));
        }

        service.seed(new WorkOrderService.SeedCmd(woNo, type, title, description,
                customerName, project, room, assignee, status, priority, deadline,
                createdAt, startedAt, completedAt, notes));
    }
}
