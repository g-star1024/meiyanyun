package com.meiyun.org.integration;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T3 数据中台 T+1 自动对账调度（DESIGN-T3 §四 #12，T3-B2）：
 * 每日 06:30（Asia/Shanghai，cron 可配置）对昨日北京日逐连接器跑本地口径对账，
 * 并追加 scope=0（全部连接器）汇总批次——无数据也落 DONE total=0 如实。
 *
 * <p>幂等：uk(connector_id, biz_date) 下重跑命中既有批次直接返回（alreadyExisted），
 * 重复触发/服务重启不产生重复批次。单连接器失败不阻断其余（逐条 try/catch 如实记日志）。
 */
@Component
public class IntegrationReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(IntegrationReconcileJob.class);
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final IntegrationConnectorRepository connectorRepo;
    private final IntegrationOutboxService outboxService;

    public IntegrationReconcileJob(IntegrationConnectorRepository connectorRepo,
                                   IntegrationOutboxService outboxService) {
        this.connectorRepo = connectorRepo;
        this.outboxService = outboxService;
    }

    @Scheduled(cron = "${meiyun.integration.reconcile-cron:0 30 6 * * ?}", zone = "Asia/Shanghai")
    public void runDailyReconcile() {
        List<IntegrationConnector> connectors = connectorRepo.findAll();
        log.info("T+1 自动对账启动：连接器 {} 个", connectors.size());
        for (IntegrationConnector connector : connectors) {
            try {
                IntegrationOutboxService.BatchView view =
                        outboxService.reconcile(connector.getId(), null, SYSTEM_ACTOR);
                log.info("T+1 自动对账 connector={} batchNo={} alreadyExisted={} total={} matched={}",
                        connector.getCode(), view.batchNo(), view.alreadyExisted(),
                        view.totalCount(), view.matchedCount());
            } catch (Exception e) {
                log.error("T+1 自动对账失败 connector={}：{}", connector.getCode(), e.getMessage());
            }
        }
        try {
            IntegrationOutboxService.BatchView summary =
                    outboxService.reconcile(IntegrationReconcileBatch.SCOPE_ALL, null, SYSTEM_ACTOR);
            log.info("T+1 自动对账汇总批次 batchNo={} alreadyExisted={} total={}",
                    summary.batchNo(), summary.alreadyExisted(), summary.totalCount());
        } catch (Exception e) {
            log.error("T+1 自动对账汇总批次失败：{}", e.getMessage());
        }
    }
}
