package com.meiyun.marketing;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 营销服务调度开关（域⑤ 赠金过期 Job 依赖）。独立新建配置类开启调度，
 * 不修改既有 MarketingApplication（避免触碰既有文件）。
 */
@Configuration
@EnableScheduling
public class MarketingSchedulerConfig {
}
