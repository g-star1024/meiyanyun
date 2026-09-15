package com.meiyun.txn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 调度派单种子占位（P5-B49 卡12，仅 meiyun_seed 库）。
 *
 * <p>设计定案：dispatch_assignment 是真实派单动作的产物，<b>不伪造历史占用</b>——
 * 种子保持空表，时间轴初始为空、待派单来自当日真实预约；演示用派单经页面真实操作产生
 * （或真验时按 DELIVER 文档登记手工补插）。此处仅保留 meiyun_seed 门控与幂等日志，
 * 与各业务 DataInitializer 顺序惯例一致（@Order 86）。
 */
@Component
@Order(86)
public class DispatchDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DispatchDataInitializer.class);

    private final DispatchAssignmentRepository repo;
    private final String datasourceUrl;

    public DispatchDataInitializer(DispatchAssignmentRepository repo,
                                   @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.repo = repo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            return;
        }
        long n = repo.count();
        if (n > 0) {
            log.info("[DispatchSeed] dispatch_assignment 已有 {} 行真实派单，跳过。", n);
            return;
        }
        log.info("[DispatchSeed] 调度派单种子保持空表（不伪造占用），待派单取当日真实预约。");
    }
}
