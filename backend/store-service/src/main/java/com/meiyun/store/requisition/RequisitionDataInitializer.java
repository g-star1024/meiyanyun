package com.meiyun.store.requisition;

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
public class RequisitionDataInitializer implements ApplicationRunner {

    private static final String APPROVER = "苏晴";

    private final RequisitionRepository rqRepo;
    private final RequisitionService service;
    private final String datasourceUrl;

    public RequisitionDataInitializer(RequisitionRepository rqRepo,
                                      RequisitionService service,
                                      @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.rqRepo = rqRepo;
        this.service = service;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) return;
        if (rqRepo.count() > 0) return;

        seedOne(1, 2, "周敏", "A03治疗室日常耗材补充", null,
                "DRAFT", null,
                List.of(l("一次性治疗巾", "40×50cm", 50, "包"),
                        l("医用棉签", "竹棒", 20, "盒")));

        seedOne(2, 5, "李娜", "B02射频项目客户使用", null,
                "SUBMITTING", null,
                List.of(l("冷凝胶", "500ml", 6, "瓶"),
                        l("一次性手套", "M码", 4, "盒")));

        seedOne(3, 20, "吴桐", "激光术后护理备货", null,
                "APPROVED", "苏晴",
                List.of(l("医用修复面膜", "6片/盒", 30, "盒"),
                        l("生理盐水", "250ml", 20, "瓶"),
                        l("纱布块", "8层", 10, "包")));

        seedOne(4, 48, "顾屿", "C01注射室一次性器械", null,
                "RECEIVED", "苏晴",
                List.of(l("一次性注射器", "1ml", 100, "支"),
                        l("医用酒精棉片", "独立包装", 200, "片")));

        String budget = "数量超出月度预算，请重新提交";
        seedOne(5, 72, "夏沫", "候诊区日常物资", budget,
                "REJECTED", "苏晴",
                List.of(l("瓶装饮用水", "350ml", 500, "瓶"),
                        l("一次性纸杯", "200ml", 20, "条")));

        seedOne(6, 8, "周敏", "美容床品换洗", null,
                "SUBMITTING", null,
                List.of(l("美容床笠", "粉色", 15, "条"),
                        l("一次性枕套", "无纺布", 60, "个")));
    }

    private void seedOne(int seq, int hoursAgo, String applicant, String purpose, String remark,
                         String status, String approver,
                         List<RequisitionService.SeedLine> lines) {
        OffsetDateTime createdAt = OffsetDateTime.now().minusHours(hoursAgo);
        String day = createdAt.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String rqNo = String.format("RQ-%s-%06d", day, seq);

        List<RequisitionService.SeedNote> notes = new ArrayList<>();
        notes.add(note(applicant, "创建申领单", createdAt));
        OffsetDateTime submittedAt = createdAt.plusHours(1);
        notes.add(note(applicant, "提交审批", submittedAt));

        OffsetDateTime approvedAt = null;
        OffsetDateTime receivedAt = null;
        String receiver = null;
        String rejectReason = null;

        switch (status) {
            case "DRAFT" -> {
                notes = new ArrayList<>();
                notes.add(note(applicant, "创建申领单", createdAt));
            }
            case "SUBMITTING" -> {
            }
            case "APPROVED" -> {
                approvedAt = submittedAt.plusHours(1);
                notes.add(note(approver, "审批通过", approvedAt));
            }
            case "RECEIVED" -> {
                approvedAt = submittedAt.plusHours(1);
                receivedAt = approvedAt.plusHours(1);
                receiver = applicant;
                notes.add(note(approver, "审批通过", approvedAt));
                notes.add(note(receiver, "已签收物料", receivedAt));
            }
            case "REJECTED" -> {
                approvedAt = submittedAt.plusHours(1);
                rejectReason = remark;
                notes.add(note(approver, "驳回：" + remark, approvedAt));
            }
            default -> {
            }
        }

        service.seed(new RequisitionService.SeedCmd(rqNo, applicant, purpose, remark,
                createdAt, status, approver, approvedAt, receiver, receivedAt,
                rejectReason, lines, notes));
    }

    private static RequisitionService.SeedLine l(String name, String spec, int qty, String unit) {
        return new RequisitionService.SeedLine(name, spec, qty, unit);
    }

    private static RequisitionService.SeedNote note(String by, String text, OffsetDateTime at) {
        return new RequisitionService.SeedNote(by, text, at);
    }
}
