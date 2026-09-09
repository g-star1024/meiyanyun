package com.meiyun.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 外部渠道回传种子（域⑤ 外部渠道 A 联调）。默认关闭，仅当 meiyun.channel.seed.enabled=true 时装配；
 * channel_returnback 为空时幂等灌入 2 条样例（抖音转化 / 小红书线索），便于联调环境演示。
 */
@Component
@Order(17)
@ConditionalOnProperty(name = "meiyun.channel.seed.enabled", havingValue = "true", matchIfMissing = false)
public class ChannelReturnbackDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChannelReturnbackDataInitializer.class);

    private final ChannelReturnbackRepository repo;

    public ChannelReturnbackDataInitializer(ChannelReturnbackRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repo.count() > 0) {
            log.info("外部渠道回传已存在（{} 条），跳过播种", repo.count());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();

        ChannelReturnback r1 = new ChannelReturnback();
        r1.setChannelCode("DOUYIN");
        r1.setEventType("CONVERSION");
        r1.setExternalUserId("ext_douyin_001");
        r1.setBizRef("ORDER-20260909-0001");
        r1.setPayload("{\"eventType\":\"CONVERSION\",\"externalUserId\":\"ext_douyin_001\",\"bizRef\":\"ORDER-20260909-0001\"}");
        r1.setStatus("RECEIVED");
        r1.setReceivedAt(now.minusHours(2));

        ChannelReturnback r2 = new ChannelReturnback();
        r2.setChannelCode("RED");
        r2.setEventType("LEAD");
        r2.setExternalUserId("ext_red_002");
        r2.setBizRef("LEAD-20260909-0002");
        r2.setPayload("{\"eventType\":\"LEAD\",\"externalUserId\":\"ext_red_002\",\"bizRef\":\"LEAD-20260909-0002\"}");
        r2.setStatus("RECEIVED");
        r2.setReceivedAt(now.minusHours(1));

        repo.save(r1);
        repo.save(r2);
        log.info("外部渠道回传播种完成：{} 条（抖音转化 / 小红书线索）", 2);
    }
}
