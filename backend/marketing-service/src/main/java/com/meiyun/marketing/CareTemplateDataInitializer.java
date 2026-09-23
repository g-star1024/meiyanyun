package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 关怀模板启动播种（P5-B90，DESIGN §2.5：marketing_cfg KV 承载，不新建表）。
 *
 * <p>幂等回填 marketing_cfg.care_templates 五模板（对齐前端 care.ts mock 活规格
 * tpl-s03/tpl-t12/tpl-p01/tpl-r02/tpl-w01 逐字内容），已有值不动（运营可经设置页改后不被覆盖）。
 */
@Component
@Order(16)
public class CareTemplateDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CareTemplateDataInitializer.class);

    private static final List<Map<String, String>> DEFAULT_TEMPLATES = List.of(
            Map.of("id", "tpl-s03", "name", "短信模板 S-03", "channel", "SMS",
                    "content", "亲爱的{昵称}，生日快乐！专属礼遇已备好，回复1领取生日礼券，到店还可享双倍积分。"),
            Map.of("id", "tpl-t12", "name", "企微图文 T-12", "channel", "WECHAT",
                    "content", "生日海报 + 到店券，企微一键推送。含本月专属项目优惠与免费皮肤检测名额。"),
            Map.of("id", "tpl-p01", "name", "电话话术 P-01", "channel", "PHONE",
                    "content", "您好，这里是美研云。{昵称}女士，本月是您的生日月，我们为您准备了专属礼遇，是否方便为您预约到店时间？"),
            Map.of("id", "tpl-r02", "name", "复购提醒 R-02", "channel", "WECHAT",
                    "content", "{昵称}女士，距离您上次护理已有 60 天，第二疗程效果更佳，本周到店享老客 8 折。"),
            Map.of("id", "tpl-w01", "name", "唤醒券 W-01", "channel", "SMS",
                    "content", "好久不见！我们为您准备了 200 元回归券，7 天内到店即可使用，期待您的光临。"));

    private final MarketingCfgRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CareTemplateDataInitializer(MarketingCfgRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        MarketingCfg cfg = repo.findById(1).orElse(null);
        if (cfg == null) {
            // cfg 行由 MarketingCfgDataInitializer（@Order(15)）先行补建；此处仍兜底防御
            cfg = new MarketingCfg();
            cfg.setCfgId(1);
            cfg.setReferralArrivedReward(200);
            cfg.setReferralDealReward(350);
            cfg.setCommissionRate(new java.math.BigDecimal("0.05"));
            cfg.setWeeklyPushLimit(3);
        }
        if (cfg.getCareTemplates() != null && !cfg.getCareTemplates().isBlank()) {
            log.info("关怀模板已存在（{} 字节），跳过播种", cfg.getCareTemplates().length());
            return;
        }
        cfg.setCareTemplates(writeJson(DEFAULT_TEMPLATES));
        repo.save(cfg);
        log.info("关怀模板播种完成：{} 条", DEFAULT_TEMPLATES.size());
    }

    private String writeJson(List<Map<String, String>> templates) {
        try {
            return objectMapper.writeValueAsString(templates);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
