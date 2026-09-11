package com.meiyun.txn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * P5-B30 术后随访 SOP 编排模板集团通用种子（仅 meiyun_seed 独立测试库播种，prod 库跳过）。
 *
 * <p>播种一个集团通用模板（store_code 为 NULL=全门店可用）+ 四个节点（术后 1/3/7/30 天），
 * 与 FollowupScheduler 内置默认节点及前端 DEFAULT_POST_OP_SOP 完全一致；即便排程读模板异常，
 * 排程器仍会回退默认节点，种子只用于让 SOP 编排页有可见可停用的真实模板行。幂等：模板表非空则跳过。</p>
 */
@Component
@Order(62)
public class FollowupSopTemplateDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FollowupSopTemplateDataInitializer.class);

    private final FollowupSopTemplateRepository templateRepo;
    private final FollowupSopTemplateNodeRepository nodeRepo;
    private final String datasourceUrl;

    public FollowupSopTemplateDataInitializer(FollowupSopTemplateRepository templateRepo,
                                              FollowupSopTemplateNodeRepository nodeRepo,
                                              @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.templateRepo = templateRepo;
        this.nodeRepo = nodeRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("[FollowupSopSeed] 非 meiyun_seed 库，跳过术后随访 SOP 模板播种。");
            return;
        }
        if (templateRepo.count() > 0) {
            log.info("[FollowupSopSeed] followup_sop_template 已有 {} 行，跳过播种。", templateRepo.count());
            return;
        }
        FollowupSopTemplate t = new FollowupSopTemplate();
        t.setTemplateNo(FollowupSopDefaults.DEFAULT_TEMPLATE_NO);
        t.setName(FollowupSopDefaults.DEFAULT_TEMPLATE_NAME);
        t.setStoreCode(null);
        t.setEnabled(true);
        t.setCreatedBy("system");
        templateRepo.save(t);

        int lineNo = 1;
        for (FollowupSopDefaults.NodeDef d : FollowupSopDefaults.NODES) {
            FollowupSopTemplateNode n = new FollowupSopTemplateNode();
            n.setTemplateNo(FollowupSopDefaults.DEFAULT_TEMPLATE_NO);
            n.setLineNo(lineNo++);
            n.setStage(d.stage());
            n.setLabel(d.label());
            n.setDayOffset(d.dayOffset());
            n.setMethod(d.method());
            n.setEnabled(true);
            nodeRepo.save(n);
        }
        log.info("[FollowupSopSeed] 已播种 1 个集团通用术后随访 SOP 模板（4 节点：1/3/7/30 天）。");
    }
}
