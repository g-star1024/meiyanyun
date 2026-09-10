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
 * P5-B30 EMR 病历模板库集团通用模板种子（仅 meiyun_seed 独立测试库播种，prod 库跳过）。
 *
 * <p>四个类型各一版集团模板（store_code 为 NULL=全门店可见），七段文本为医美门诊常用采集提纲，
 * 仅作录入提效示范，门店可在模板库端点自建/停用本店模板。幂等：表非空则跳过。</p>
 */
@Component
@Order(61)
public class EmrTemplateDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmrTemplateDataInitializer.class);

    private final EmrTemplateRepository repo;
    private final String datasourceUrl;

    public EmrTemplateDataInitializer(EmrTemplateRepository repo,
                                      @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.repo = repo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("[EmrTemplateSeed] 非 meiyun_seed 库，跳过病历模板播种。");
            return;
        }
        if (repo.count() > 0) {
            log.info("[EmrTemplateSeed] emr_template 已有 {} 行，跳过播种。", repo.count());
            return;
        }
        seed("EMT-SEED-001", "初诊标准病历模板", "FIRST_VISIT",
                "面部肤色暗沉、毛孔粗大，咨询光电类改善项目",
                "发病/求美经过、既往同类治疗史、当前主要困扰与诉求；近期护肤与日晒情况",
                "否认高血压、糖尿病等基础疾病；否认瘢痕体质；否认面部植入物",
                "否认药物及化妆品过敏史",
                "面部光老化、毛孔粗大（Fitzpatrick 分型以面诊为准）",
                "建议光子嫩肤/水光等光电联合治疗，具体能量参数以面诊与术中反应为准",
                "术后即刻冷敷；严格防晒（SPF50+）；加强保湿；7 日内避免高温桑拿与刺激性护肤品；按约复诊");
        seed("EMT-SEED-002", "复诊评估模板", "FOLLOW_UP",
                "光电治疗后复查，反馈恢复情况",
                "距上次治疗天数；术后红肿/结痂/色沉变化；护理与防晒依从性",
                "同既往史，无新增",
                "否认药物及化妆品过敏史",
                "术后恢复中，皮肤屏障逐步修复（以面诊评估为准）",
                "评估后安排下一疗程；维持光电联合治疗节奏",
                "继续严格防晒与保湿；出现水疱、持续红肿及时复诊");
        seed("EMT-SEED-003", "治疗记录模板", "TREATMENT",
                "按方案单来院行治疗",
                "本次为方案内第 N 次治疗；术前再次确认诉求与禁忌",
                "同既往史；术前四项核对通过",
                "过敏史以知情同意书复核为准",
                "同方案诊断结论",
                "治疗项目、部位、能量/剂量、术中反应与即刻处理据实记录",
                "治疗部位冷敷；禁水 24 小时（按项目）；严格防晒；按约复查");
        seed("EMT-SEED-004", "操作记录模板", "PROCEDURE",
                "行注射/微创操作，记录操作过程",
                "操作部位、药品/耗材批号、剂量配比；术中患者配合情况",
                "同既往史；否认出血倾向",
                "过敏史以知情同意书复核为准",
                "操作部位评估与适应症确认",
                "操作入针点位、层次、单次剂量、总用量；术中术后即刻情况据实记录",
                "按压观察 30 分钟；6 小时内避免平卧/揉搓；忌辛辣酒精；异常及时联系");
        log.info("[EmrTemplateSeed] 已播种 4 个集团通用病历模板（初诊/复诊/治疗/操作）。");
    }

    private void seed(String no, String name, String type,
                      String chiefComplaint, String presentIllness, String pastHistory,
                      String allergy, String diagnosis, String treatment, String prescription) {
        EmrTemplate t = new EmrTemplate();
        t.setTemplateNo(no);
        t.setName(name);
        t.setType(type);
        t.setChiefComplaint(chiefComplaint);
        t.setPresentIllness(presentIllness);
        t.setPastHistory(pastHistory);
        t.setAllergy(allergy);
        t.setDiagnosis(diagnosis);
        t.setTreatment(treatment);
        t.setPrescription(prescription);
        t.setStoreCode(null);
        t.setEnabled(true);
        t.setCreatedBy("system");
        repo.save(t);
    }
}
