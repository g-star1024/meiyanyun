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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 营销活动启动播种（M5-14 看板真实化）：campaign 表为空时幂等灌入 6 个活动，
 * 覆盖 FULL_REDUCE/DISCOUNT/COUPON_PACK/GIFT/NEWBIE/VIP_DAY 六类与 RUNNING/ENDED/SCHEDULED 三态。
 * 金额口径：budget/spent/targetAmount/actualAmount bigint 存「分」（活规格为元，×100）。
 * channels 为渠道中文名 JSON 数组文本（与 CampaignService.toJsonArray 落库格式一致）；
 * 活动 ID 用 CP-SEED-xxx（种子固定号，用户新建走 BizNoGenerator 的 CP 前缀）。
 */
@Component
@Order(16)
public class CampaignDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CampaignDataInitializer.class);

    private final CampaignRepository campaignRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CampaignDataInitializer(CampaignRepository campaignRepo) {
        this.campaignRepo = campaignRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (campaignRepo.count() > 0) {
            log.info("营销活动已存在（{} 个），跳过播种", campaignRepo.count());
            return;
        }

        LocalDate today = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now();
        List<Campaign> campaigns = new ArrayList<>();

        // 名称 类型 状态 渠道 起(相对天) 止(相对天) 预算 已花费 目标 实际成交 新客 负责人
        campaigns.add(campaign("CP-SEED-001", "暑期水光自由卡季", "FULL_REDUCE", "RUNNING",
                List.of("抖音", "微信私域", "美团"), -20, 10,
                200000, 86000, 600000, 412000, 186, "林微", today, now));
        campaigns.add(campaign("CP-SEED-002", "新客 88 元体验礼", "NEWBIE", "ENDED",
                List.of("小红书", "大众点评", "微信私域"), -45, -10,
                80000, 52000, 200000, 168000, 240, "苏晴", today, now));
        campaigns.add(campaign("CP-SEED-003", "周三会员日双倍积分", "VIP_DAY", "ENDED",
                List.of("微信私域", "视频号"), -33, -30,
                30000, 18000, 120000, 96000, 64, "陈雅琳", today, now));
        campaigns.add(campaign("CP-SEED-004", "热玛吉抗衰专场", "DISCOUNT", "RUNNING",
                List.of("抖音", "小红书"), -6, 24,
                150000, 44000, 500000, 186000, 58, "白桥", today, now));
        campaigns.add(campaign("CP-SEED-005", "老带新双赢券包", "COUPON_PACK", "ENDED",
                List.of("微信私域"), -52, -18,
                60000, 38000, 180000, 142000, 96, "李娜", today, now));
        campaigns.add(campaign("CP-SEED-006", "到店赠品礼", "GIFT", "SCHEDULED",
                List.of("美团", "大众点评"), 4, 20,
                40000, 0, 100000, 0, 0, "王芳", today, now));

        campaignRepo.saveAll(campaigns);
        log.info("营销活动播种完成：{} 个（六类活动 / 进行中 {} / 已结束 {} / 待开始 {}）",
                campaigns.size(),
                campaigns.stream().filter(c -> "RUNNING".equals(c.getStatus())).count(),
                campaigns.stream().filter(c -> "ENDED".equals(c.getStatus())).count(),
                campaigns.stream().filter(c -> "SCHEDULED".equals(c.getStatus())).count());
    }

    private Campaign campaign(String id, String name, String type, String status, List<String> channels,
                              int startOffset, int endOffset,
                              long budgetYuan, long spentYuan, long targetYuan, long actualYuan,
                              int newCustomers, String owner, LocalDate today, OffsetDateTime now) {
        Campaign c = new Campaign();
        c.setCampaignId(id);
        c.setCampaignName(name);
        c.setCampaignType(type);
        c.setStatus(status);
        try {
            c.setChannels(objectMapper.writeValueAsString(channels));
        } catch (JsonProcessingException e) {
            c.setChannels("[]");
        }
        c.setStartDate(today.plusDays(startOffset));
        c.setEndDate(today.plusDays(endOffset));
        c.setBudget(budgetYuan * 100);
        c.setSpent(spentYuan * 100);
        c.setTargetAmount(targetYuan * 100);
        c.setActualAmount(actualYuan * 100);
        c.setNewCustomers(newCustomers);
        c.setStoreScope("全部门店");
        c.setOwner(owner);
        c.setRemark("种子活动数据");
        c.setCreatedAt(now);
        return c;
    }
}
