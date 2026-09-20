package com.meiyun.store.wastage;

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
@Order(80)
public class WastageDataInitializer implements ApplicationRunner {

    private static final String APPROVER = "苏晴";
    private static final String REJECT_FULL = "建议先走设备维修工单，再按维修结果处理资产报废";

    private final WastageRepository wsRepo;
    private final WastageService service;
    private final String datasourceUrl;

    public WastageDataInitializer(WastageRepository wsRepo,
                                  WastageService service,
                                  @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.wsRepo = wsRepo;
        this.service = service;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) return;
        if (wsRepo.count() > 0) return;

        seedOne(1, 20, "APPROVED", "EXPIRED", "医用修复面膜", "6 片/盒", 2, "盒", 33600,
                "吴桐", "耗材仓 B 区", "库存盘点发现 2 盒已过保质期");
        seedOne(2, 3, "SUBMITTING", "BROKEN", "冷凝胶", "500ml", 1, "瓶", 18000,
                "周敏", "B02 治疗室", "取用过程中不慎跌落，瓶身破裂无法使用");
        seedOne(3, 6, "SUBMITTING", "EXPIRED", "玻尿酸原液", "1ml/支", 5, "支", 425000,
                "顾屿", "冷藏柜 1", "冷链断电 4 小时，整盒效期受影响报废");
        seedOne(4, 72, "APPROVED", "INVENTORY_LOSS", "一次性注射器", "1ml", 20, "支", 4000,
                "苏晴", "耗材仓", "月末盘点账实不符，差异 20 支");
        seedOne(5, 96, "REJECTED", "BROKEN", "射频治疗手柄", "标配", 1, "个", 380000,
                "李娜", "B02 治疗室", "客户治疗过程中手柄异常发热，检修判定主板损坏");
        seedOne(6, 1, "DRAFT", "OTHER", "瓶装饮用水", "350ml", 12, "瓶", 3600,
                "夏沫", "候诊区", "包装破损污染，无法提供给客户");
        seedOne(7, 120, "APPROVED", "EXPIRED", "医用酒精棉片", "独立包装", 30, "片", 1500,
                "顾屿", "C01 注射室", "独立包装上有效期已过");
    }

    private void seedOne(int seq, int hoursAgo, String status, String reason, String itemName,
                         String spec, int qty, String unit, long amountFen, String reporter,
                         String location, String description) {
        OffsetDateTime createdAt = OffsetDateTime.now().minusHours(hoursAgo);
        String day = createdAt.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String wsNo = String.format("WS-%s-%06d", day, seq);

        List<WastageService.SeedNote> notes = new ArrayList<>();
        notes.add(note(reporter, "创建报损单", createdAt));

        String approver = null;
        OffsetDateTime approvedAt = null;
        String rejectReason = null;

        if (!"DRAFT".equals(status)) {
            notes.add(note(reporter, "提交审批", OffsetDateTime.now().minusHours(seq + 1)));
        }
        if ("APPROVED".equals(status)) {
            approver = APPROVER;
            approvedAt = OffsetDateTime.now().minusHours(Math.max(seq - 1, 1));
            notes.add(note(APPROVER, "审批通过", approvedAt));
        }
        if ("REJECTED".equals(status)) {
            approver = APPROVER;
            approvedAt = OffsetDateTime.now().minusHours(Math.max(seq - 1, 1));
            rejectReason = REJECT_FULL;
            notes.add(note(APPROVER, "驳回：" + REJECT_FULL, approvedAt));
        }

        service.seed(new WastageService.SeedCmd(wsNo, status, reason, itemName, spec,
                qty, unit, amountFen, reporter, location, description, approver, rejectReason,
                createdAt, approvedAt, createdAt, notes));
    }

    private static WastageService.SeedNote note(String by, String text, OffsetDateTime at) {
        return new WastageService.SeedNote(by, text, at);
    }
}
