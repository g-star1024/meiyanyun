package com.meiyun.store.sop;

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
import java.util.List;
import java.util.Map;

/**
 * SOP 域种子（B49 卡6，仅 meiyun_seed 库；模板 count &gt; 0 幂等跳过）。
 *
 * <p>模板为静态主数据，由 Repository 直建以精确镜像 mock 的 code/version/updatedAt 字面量；
 * 任务全走 {@link SopService} 同路径（派单 → 开始 → 勾选 → 完成），保证状态机/审计与线上同一路径。
 *
 * <p>门店映射（mock T 系 → seed SST 系）：T01→SST01 杭州、T02→SST02 上海、T03→SST03 北京、
 * T04→SST04 广州、T05→SST05 成都。截止日重定 2026-09-08 ~ 09-25，保证四态派生齐备：
 * PENDING 2（TK02/TK05）、IN_PROGRESS 1（TK01）、DONE 1（TK04）、OVERDUE 派生 2（TK03 待执行逾期、
 * TK06 进行中逾期）。
 */
@Component
@Order(70)
public class SopDataInitializer implements ApplicationRunner {

    private final SopTemplateRepository templateRepo;
    private final SopTemplateStepRepository stepRepo;
    private final SopService sopService;
    private final String datasourceUrl;

    public SopDataInitializer(SopTemplateRepository templateRepo,
                              SopTemplateStepRepository stepRepo,
                              SopService sopService,
                              @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.templateRepo = templateRepo;
        this.stepRepo = stepRepo;
        this.sopService = sopService;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            return;
        }
        if (templateRepo.count() > 0) {
            return;
        }
        seedTemplates();
        seedTasks();
    }

    // ============================================================
    // 模板（5 模板 18 步，镜像 mock；插入顺序即 id S01~S05）
    // ============================================================

    private void seedTemplates() {
        seedTemplate("SOP-M-001", "术前知情同意签署规范", "MEDICAL", "v3.2", "PUBLISHED", "医务部",
                "2026-08-10", List.of("ALL"),
                new String[][]{
                        {"核验客户身份与项目", "核对身份证、订单项目与主治医生", "0"},
                        {"充分告知风险与替代方案", "逐条讲解知情同意书，答疑不少于 10 分钟", "1"},
                        {"客户本人签署", "客户手写签名并按手印，禁止代签", "0"},
                        {"医生双签归档", "主治医生与见证护士共同签字，扫描入 EMR", "0"},
                });
        seedTemplate("SOP-H-002", "医疗器械高温高压消毒流程", "HYGIENE", "v2.1", "PUBLISHED", "感控办",
                "2026-07-28", List.of("ALL"),
                new String[][]{
                        {"器械预处理清洗", "使用后立即酶洗液浸泡 5 分钟", "0"},
                        {"封装与化学指示卡", "封装后内置 5 类化学指示卡", "1"},
                        {"高温高压灭菌", "134℃ 灭菌 4 分钟，记录批次号", "0"},
                        {"生物监测", "每周一次嗜热脂肪杆菌生物监测并留档", "0"},
                });
        seedTemplate("SOP-S-003", "过敏性休克应急处置", "SAFETY", "v1.5", "PUBLISHED", "安全委员会",
                "2026-06-15", List.of("ALL"),
                new String[][]{
                        {"立即停止操作并呼救", "平卧、抬腿、保暖", "0"},
                        {"肾上腺素肌注", "0.1% 肾上腺素 0.3-0.5ml 大腿外侧肌注", "1"},
                        {"建立静脉通路与吸氧", "生理盐水快速补液，高流量吸氧", "0"},
                        {"拨打 120 并持续监护", "记录生命体征，转诊交接", "0"},
                });
        seedTemplate("SOP-SV-004", "到店接待与分诊标准", "SERVICE", "v2.0", "PUBLISHED", "运营中心",
                "2026-08-01", List.of("SST01", "SST02", "SST04"),
                new String[][]{
                        {"3 秒迎宾", "客户进门 3 秒内主动问候", "0"},
                        {"建档与预约核对", "核对预约信息，更新客情", "0"},
                        {"引导至休息区", "奉上饮品，告知预计等待时间", "0"},
                });
        seedTemplate("SOP-MG-005", "日结收银对账流程", "MANAGEMENT", "v1.2", "DRAFT", "财务部",
                "2026-08-20", List.of("ALL"),
                new String[][]{
                        {"打印当日流水", "汇总现金/刷卡/扫码/分期", "0"},
                        {"账实核对", "现金盘点与系统流水逐笔核对", "0"},
                        {"差异说明与签字", "差异需店长签字说明原因", "0"},
                });
    }

    private void seedTemplate(String code, String title, String category, String version, String status,
                              String owner, String updatedAt, List<String> applicableStores,
                              String[][] steps) {
        SopTemplate t = new SopTemplate();
        t.setCode(code);
        t.setTitle(title);
        t.setCategory(category);
        t.setVersion(version);
        t.setStatus(status);
        t.setOwner(owner);
        t.setApplicableStores(String.join(",", applicableStores));
        templateRepo.save(t);
        t.setUpdatedAt(OffsetDateTime.of(LocalDate.parse(updatedAt), LocalTime.MIN, ZoneOffset.ofHours(8)));
        templateRepo.save(t);

        int no = 0;
        for (String[] s : steps) {
            SopTemplateStep step = new SopTemplateStep();
            step.setTemplateId(t.getId());
            step.setStepNo(++no);
            step.setTitle(s[0]);
            step.setDescription(s[1]);
            step.setRequirePhoto("1".equals(s[2]));
            stepRepo.save(step);
        }
    }

    // ============================================================
    // 任务（6 条，全走 service 同路径；插入顺序即 id TK01~TK06）
    // ============================================================

    private void seedTasks() {
        String op = "系统 seed";

        Map<String, Object> tk1 = sopService.createTask("S02", "SST02", "王护士长", "HIGH", "2026-09-20", op);
        String tk1Id = (String) tk1.get("id");
        sopService.startTask(tk1Id, op);
        sopService.toggleStep(tk1Id, "s1", op);
        sopService.toggleStep(tk1Id, "s2", op);

        sopService.createTask("S01", "SST01", "顾医生", "HIGH", "2026-09-18", op);

        sopService.createTask("S03", "SST03", "张院长", "HIGH", "2026-09-10", op);

        Map<String, Object> tk4 = sopService.createTask("S04", "SST04", "陈前台", "MEDIUM", "2026-09-13", op);
        String tk4Id = (String) tk4.get("id");
        sopService.startTask(tk4Id, op);
        sopService.toggleStep(tk4Id, "s1", op);
        sopService.toggleStep(tk4Id, "s2", op);
        sopService.toggleStep(tk4Id, "s3", op);
        sopService.completeTask(tk4Id, "当日接待 42 人，流程执行良好", op);

        sopService.createTask("S02", "SST05", "赵护士", "MEDIUM", "2026-09-25", op);

        Map<String, Object> tk6 = sopService.createTask("S01", "SST02", "李医生", "HIGH", "2026-09-08", op);
        String tk6Id = (String) tk6.get("id");
        sopService.startTask(tk6Id, op);
        sopService.toggleStep(tk6Id, "s1", op);
    }
}
