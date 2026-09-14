package com.meiyun.store.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 健康度域种子（B49 卡10，仅 meiyun_seed 库；health_check count &gt; 0 幂等跳过）。
 *
 * <p>四态任务结构简单（无 SOP 步骤勾选派生），全部 Repository 直建终态、不落审计，
 * 审计从真验操作起始（与卡6 差异已在交付章如实标注）。
 *
 * <p>门店映射（mock T 系 → seed SST 系）：T02→SST02 上海浦东、T03→SST03 北京国贸、
 * T04→SST04 广州天河、T05→SST05 成都春熙；SST06 杭州西湖店无种子档案不出现列表。
 * 日期重定 2026-09 区间。issue id 由 IDENTITY 自增派生 I01-I07，镜像 mock 字面量。
 */
@Component
@Order(80)
public class HealthDataInitializer implements ApplicationRunner {

    private static final List<String> DIMS = HealthService.DIMS;

    private final HealthCheckRepository checkRepo;
    private final HealthScoreRepository scoreRepo;
    private final HealthIssueRepository issueRepo;
    private final String datasourceUrl;

    public HealthDataInitializer(HealthCheckRepository checkRepo,
                                 HealthScoreRepository scoreRepo,
                                 HealthIssueRepository issueRepo,
                                 @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.checkRepo = checkRepo;
        this.scoreRepo = scoreRepo;
        this.issueRepo = issueRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            return;
        }
        if (checkRepo.count() > 0) {
            return;
        }
        seedChecks();
        seedIssues();
    }

    private void seedChecks() {
        seedStore("SST01", new int[]{92, 88, 90, 95, 86, 84},
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 17), "王质控");
        seedStore("SST02", new int[]{78, 82, 65, 88, 72, 70},
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 16), "李质控");
        seedStore("SST03", new int[]{62, 75, 58, 70, 68, 55},
                LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 18), "张质控");
        seedStore("SST04", new int[]{88, 90, 82, 91, 85, 80},
                LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 19), "陈质控");
        seedStore("SST05", new int[]{85, 80, 76, 84, 78, 90},
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 20), "赵质控");
    }

    private void seedStore(String storeCode, int[] scores, LocalDate last, LocalDate next, String inspector) {
        HealthCheck c = new HealthCheck();
        c.setStoreCode(storeCode);
        c.setLastCheckedAt(last);
        c.setNextCheckAt(next);
        c.setInspector(inspector);
        checkRepo.save(c);
        for (int i = 0; i < DIMS.size(); i++) {
            HealthScore s = new HealthScore();
            s.setStoreCode(storeCode);
            s.setDimension(DIMS.get(i));
            s.setScore(scores[i]);
            s.setWeight(weightOf(DIMS.get(i)));
            scoreRepo.save(s);
        }
    }

    private static int weightOf(String dimension) {
        return ("SAFETY".equals(dimension) || "COMPLIANCE".equals(dimension)) ? 2 : 1;
    }

    private void seedIssues() {
        seedIssue("SST03", "EQUIPMENT", "HIGH", "热玛吉设备超期未校准",
                "设备编号 RMJ-003 上次校准 2026-05-10，已超期 107 天，存在治疗安全隐患。",
                "OPEN", "张院长", LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 2), null, null);
        seedIssue("SST03", "FINANCE", "HIGH", "应收账款周转异常",
                "应收账款周转天数 62 天，超出集团红线 45 天，逾期款占比 28%。",
                "PROCESSING", "刘财务", LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 2), null, null);
        seedIssue("SST02", "STAFF", "MEDIUM", "主诊医师配比不足",
                "在岗主诊医师 3 人，按日均客流 80 人标准需 5 人，已启动招聘。",
                "PROCESSING", "李院长", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 3), null, null);
        seedIssue("SST02", "EQUIPMENT", "MEDIUM", "消毒记录不完整",
                "8 月有 3 天高温高压消毒记录缺失生物监测结果。",
                "OPEN", "王护士长", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 3), null, null);
        seedIssue("SST03", "SAFETY", "HIGH", "急救药品近效期",
                "肾上腺素 2 支、硝酸甘油 1 支将在 15 天内到期，需立即更换。",
                "OPEN", "张院长", LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 4), null, null);
        seedIssue("SST05", "SERVICE", "LOW", "客户满意度环比下降",
                "7 月满意度 91% 降至 88%，主要投诉集中在等待时长。",
                "RESOLVED", "赵院长", null, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 12), "已增加周末排班，增开 2 间治疗室分流。");
        seedIssue("SST04", "COMPLIANCE", "MEDIUM", "广告素材备案滞后",
                "3 条线上推广素材上线前未完成医疗广告审查备案。",
                "PROCESSING", "陈运营", LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 4), null, null);
    }

    private void seedIssue(String storeCode, String dimension, String severity, String title,
                           String detail, String status, String assignee, LocalDate dueAt,
                           LocalDate createdAt, LocalDate resolvedAt, String resolution) {
        HealthIssue i = new HealthIssue();
        i.setStoreCode(storeCode);
        i.setDimension(dimension);
        i.setSeverity(severity);
        i.setTitle(title);
        i.setDetail(detail);
        i.setStatus(status);
        i.setAssignee(assignee);
        i.setDueAt(dueAt);
        i.setCreatedAt(createdAt.atStartOfDay().atOffset(ZoneOffset.ofHours(8)));
        i.setResolvedAt(resolvedAt);
        i.setResolution(resolution);
        issueRepo.save(i);
    }
}
