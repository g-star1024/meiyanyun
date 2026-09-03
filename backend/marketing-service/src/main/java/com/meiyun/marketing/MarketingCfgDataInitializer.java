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

import java.math.BigDecimal;
import java.util.List;

/**
 * 营销全局配置启动播种（M5-15）。
 *
 * <p>种子库 01_master.sql 已插入 cfgId=1 基础行（老带新奖励/佣金/周频），但新补的
 * 免打扰/审批流/默认渠道九列为空；本初始化器幂等回填默认值，已有值的字段不动，
 * 行不存在则补建一条完整默认行。默认值与前端 DEFAULT_SETTINGS 活规格对齐。
 */
@Component
@Order(15)
public class MarketingCfgDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketingCfgDataInitializer.class);

    private final MarketingCfgRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MarketingCfgDataInitializer(MarketingCfgRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        MarketingCfg cfg = repo.findById(1).orElse(null);
        boolean created = false;
        if (cfg == null) {
            cfg = new MarketingCfg();
            cfg.setCfgId(1);
            // 基础默认值（与 01_master.sql 口径一致，供正式库无种子行时兜底）
            cfg.setReferralArrivedReward(200);
            cfg.setReferralDealReward(350);
            cfg.setCommissionRate(new BigDecimal("0.05"));
            cfg.setWeeklyPushLimit(3);
            created = true;
        }
        int filled = 0;
        if (cfg.getWeeklyPushLimit() == null) {
            cfg.setWeeklyPushLimit(3);
            filled++;
        }
        if (cfg.getQuietHoursEnabled() == null) {
            cfg.setQuietHoursEnabled(true);
            filled++;
        }
        if (cfg.getQuietStart() == null) {
            cfg.setQuietStart("21:00");
            filled++;
        }
        if (cfg.getQuietEnd() == null) {
            cfg.setQuietEnd("09:00");
            filled++;
        }
        if (cfg.getHolidayExempt() == null) {
            cfg.setHolidayExempt(true);
            filled++;
        }
        if (cfg.getLargeCouponThresholdFen() == null) {
            cfg.setLargeCouponThresholdFen(100_000L); // 1000 元 = 100000 分
            filled++;
        }
        if (cfg.getPushRequiresApproval() == null) {
            cfg.setPushRequiresApproval(true);
            filled++;
        }
        if (cfg.getApprovalLevel() == null) {
            cfg.setApprovalLevel(2);
            filled++;
        }
        if (cfg.getDefaultPushChannels() == null || cfg.getDefaultPushChannels().isBlank()) {
            cfg.setDefaultPushChannels(writeJson(List.of("WECOM")));
            filled++;
        }
        if (cfg.getDefaultAdChannels() == null || cfg.getDefaultAdChannels().isBlank()) {
            cfg.setDefaultAdChannels(writeJson(List.of("抖音", "微信私域")));
            filled++;
        }
        if (created || filled > 0) {
            repo.save(cfg);
            log.info("营销配置播种完成：{}，回填默认字段 {} 个", created ? "新建默认行" : "复用既有行", filled);
        } else {
            log.info("营销配置已完整，跳过播种");
        }
    }

    private String writeJson(List<String> channels) {
        try {
            return objectMapper.writeValueAsString(channels);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
