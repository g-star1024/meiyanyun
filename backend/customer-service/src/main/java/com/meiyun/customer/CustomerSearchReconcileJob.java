package com.meiyun.customer;

import com.meiyun.customer.search.CustomerSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 客户搜索索引定时对账巡检（04-backlog ES reindex 观察项治理）：
 * 每 6 小时 diff PG ↔ ES 文档级差异；PG 有 ES 无（missing）自动补写并记 info，
 * ES 有 PG 无（orphan，含 seed 栈共享索引的 SC* 文档）仅记 warn 待人工确认，不自动删除。
 * ES 不可达时本轮跳过，下轮重试。
 */
@Component
public class CustomerSearchReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(CustomerSearchReconcileJob.class);

    private final CustomerSearchService searchService;

    public CustomerSearchReconcileJob(CustomerSearchService searchService) {
        this.searchService = searchService;
    }

    @Scheduled(fixedDelay = 21_600_000L, initialDelay = 300_000L) // 6h，启动 5 分钟后首跑
    public void reconcile() {
        try {
            CustomerSearchService.ReconcileResult r = searchService.reconcile();
            if (r.fixed() > 0 || !r.missing().isEmpty()) {
                log.info("search reconcile: pg={} es={} missing={} fixed={}", r.pgCount(), r.esCount(),
                        r.missing().size(), r.fixed());
            }
            if (!r.orphan().isEmpty()) {
                log.warn("search reconcile orphan(ES 有 PG 无，含 seed 共享索引文档，不自动删除): count={} sample={}",
                        r.orphan().size(), r.orphan().stream().limit(5).toList());
            }
        } catch (Exception e) {
            log.warn("search reconcile skipped this round: {}", e.getMessage());
        }
    }
}
