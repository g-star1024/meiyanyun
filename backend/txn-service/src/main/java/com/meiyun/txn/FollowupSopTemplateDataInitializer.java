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
 * 排程器仍会回退默认节点，种子只用于让 SOP 编排页有可见可停用的真实模板行。</p>
 *
 * <p>P6-B101 多模板：补播一个门店模板（SPT-SEED-101，store_code=SST01 上海徐汇店），节点自内置默认
 * 复制并全启用，用于门店级模板/节点重排联调与排程「本店优先」真验。幂等按模板号分行判断：
 * 已存在的模板跳过且不覆盖人工改动。</p>
 */
@Component
@Order(62)
public class FollowupSopTemplateDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FollowupSopTemplateDataInitializer.class);

    /** 门店模板种子：SST01 上海徐汇店术后随访 SOP，节点自内置默认四节点复制。 */
    private static final String STORE_TEMPLATE_NO = "SPT-SEED-101";
    private static final String STORE_TEMPLATE_NAME = "徐汇店术后随访 SOP";
    private static final String STORE_TEMPLATE_STORE = "SST01";

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
        seedTemplate(FollowupSopDefaults.DEFAULT_TEMPLATE_NO, FollowupSopDefaults.DEFAULT_TEMPLATE_NAME, null);
        seedTemplate(STORE_TEMPLATE_NO, STORE_TEMPLATE_NAME, STORE_TEMPLATE_STORE);
    }

    /** 按模板号幂等播种一个模板 + 内置默认四节点（全启用）；已存在则跳过，不覆盖人工改动。 */
    private void seedTemplate(String templateNo, String name, String storeCode) {
        if (templateRepo.existsById(templateNo)) {
            log.info("[FollowupSopSeed] 模板 {} 已存在，跳过播种。", templateNo);
            return;
        }
        FollowupSopTemplate t = new FollowupSopTemplate();
        t.setTemplateNo(templateNo);
        t.setName(name);
        t.setStoreCode(storeCode);
        t.setEnabled(true);
        t.setCreatedBy("system");
        templateRepo.save(t);

        int lineNo = 1;
        for (FollowupSopDefaults.NodeDef d : FollowupSopDefaults.NODES) {
            FollowupSopTemplateNode n = new FollowupSopTemplateNode();
            n.setTemplateNo(templateNo);
            n.setLineNo(lineNo++);
            n.setStage(d.stage());
            n.setLabel(d.label());
            n.setDayOffset(d.dayOffset());
            n.setMethod(d.method());
            n.setEnabled(true);
            nodeRepo.save(n);
        }
        log.info("[FollowupSopSeed] 已播种模板 {}（{}，归属={}，4 节点：1/3/7/30 天）。",
                templateNo, name, storeCode == null ? "集团通用" : storeCode);
    }
}
