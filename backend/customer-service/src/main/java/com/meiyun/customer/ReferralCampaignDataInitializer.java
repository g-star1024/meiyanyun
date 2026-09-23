package com.meiyun.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 转介绍活动启动播种（P5-B89）：referral_campaign / referral_campaign_config 为空时
 * 幂等灌入 1 个 ONGOING 种子活动（RC-SEED-001）＋ GLOBAL 配置行（默认 ladders 三档 /
 * levels 两级 / 话术），与前端 m5ReferralCampaign store 活规格逐字一致
 *（奖励形式 CASH、有效期 30 天、话术、阶梯 1/3/5 人、层级 5%/2% 全对齐）。
 *
 * <p><b>栈门控</b>：RC-SEED 固定号演示数据仅与 seed 栈自洽，仅在种子库
 *（JDBC URL 含 meiyun_seed）播种，与 marketing 各 DataInitializer 同一门控约定；
 * 正式栈活动/配置由运营在老带新页真实维护。
 */
@Component
@Order(41)
public class ReferralCampaignDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReferralCampaignDataInitializer.class);

    private final ReferralCampaignRepository campaignRepo;
    private final ReferralCampaignConfigRepository configRepo;
    private final String datasourceUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReferralCampaignDataInitializer(ReferralCampaignRepository campaignRepo,
                                           ReferralCampaignConfigRepository configRepo,
                                           @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.campaignRepo = campaignRepo;
        this.configRepo = configRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过转介绍活动演示数据播种；正式栈活动/配置由运营真实维护",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        if (campaignRepo.count() > 0 || configRepo.count() > 0) {
            log.info("转介绍活动已存在（活动 {} / 配置 {}），跳过播种", campaignRepo.count(), configRepo.count());
            return;
        }

        ReferralCampaign c = new ReferralCampaign();
        c.setCampaignId("RC-SEED-001");
        c.setName("2026 春季老带新");
        c.setStatus("ONGOING");
        c.setStartAt(LocalDate.now().minusDays(20));
        c.setEndAt(LocalDate.now().plusDays(40));
        c.setStoreCode(null);
        c.setCreatedBy("系统");
        campaignRepo.save(c);

        ReferralCampaignConfig cfg = new ReferralCampaignConfig();
        cfg.setConfigId("GLOBAL");
        cfg.setRewardType("CASH");
        cfg.setValidDays(30);
        cfg.setScript("我在美研云体验很不错，推荐你也来看看，到店我们都能拿奖励～");
        cfg.setLadders(toJson(List.of(
                Map.of("threshold", 1, "type", "CASH", "amount", 100, "desc", "邀请 1 人到店，奖励 100 元现金"),
                Map.of("threshold", 3, "type", "COUPON", "amount", 300, "desc", "邀请 3 人成交，加赠 300 元项目券"),
                Map.of("threshold", 5, "type", "POINTS", "amount", 5000, "desc", "邀请 5 人成交，额外 5000 积分"))));
        cfg.setLevels(toJson(List.of(
                Map.of("level", 1, "rate", 0.05, "desc", "一级推荐（直接邀请）成交返 5%"),
                Map.of("level", 2, "rate", 0.02, "desc", "二级推荐（好友再邀）成交返 2%"))));
        cfg.setUpdatedBy("系统");
        configRepo.save(cfg);

        log.info("转介绍活动播种完成：活动 1 个（ONGOING）＋ GLOBAL 配置行（CASH / 30 天 / 阶梯 3 档 / 层级 2 级）");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
