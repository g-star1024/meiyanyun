package com.meiyun.txn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * txn-service 定时任务调度池（域⑦ D5）。
 *
 * <p>{@code @Scheduled} 默认共用单线程调度器：通知扇出 Job 对短信/企微/邮件 dev 网关做同步 HTTP，
 * 网关慢/超时会长期独占唯一调度线程，拖死同服务的 ApprovalSlaJob/FinanceEventRetryJob 等既有任务。
 * 故在此显式提供一个多线程 {@link ThreadPoolTaskScheduler}（Spring Boot 3.2 检测到用户自定义
 * TaskScheduler 后即不再装配默认单线程池），所有 txn {@code @Scheduled} 任务共享该池、互不阻塞。
 *
 * <p>仅新增本配置类，未改动 TxnApplication 的 {@code @EnableScheduling}。
 */
@Configuration
public class TxnSchedulerConfig {

    @Value("${meiyun.scheduler.pool-size:4}")
    private int poolSize;

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(2, poolSize));
        scheduler.setThreadNamePrefix("txn-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.initialize();
        return scheduler;
    }
}
