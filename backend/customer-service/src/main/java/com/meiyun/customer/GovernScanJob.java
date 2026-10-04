package com.meiyun.customer;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据治理质量规则定时扫描（T2-B1 / DESIGN-T2）。
 *
 * <p>范式复刻本服务 {@link TagAutoRuleJob}：限批 50 条规则 FIFO、单条规则 try-catch、
 * 不加方法级 @Transactional（每条规则独立 runRuleQuiet 内部事务，单条失败不回滚整批）、SLF4J 日志。
 * 与标签规则两点差异：①节奏 5 分钟一轮（治理规则全表 count/group by 比标签求值重，不宜分钟级）；
 * ②审计收敛在 runRuleQuiet 内部（仅违规>0 才落 RUN 汇总），Job 本体只记日志。
 * 仅执行「启用」的规则（id 升序=种子插入序）；执行异常收敛为 RuleRunResult.error 不中断同批。
 */
@Component
public class GovernScanJob {

    private static final Logger log = LoggerFactory.getLogger(GovernScanJob.class);

    private final DataGovernRuleRepository ruleRepository;
    private final T2GovernService governService;

    public GovernScanJob(DataGovernRuleRepository ruleRepository, T2GovernService governService) {
        this.ruleRepository = ruleRepository;
        this.governService = governService;
    }

    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void scan() {
        List<DataGovernRule> batch = ruleRepository.findByEnabledTrueOrderByIdAsc().stream()
                .limit(50).toList();
        if (batch.isEmpty()) return;
        for (DataGovernRule r : batch) {
            try {
                T2GovernService.RuleRunResult res = governService.runRuleQuiet(r);
                if (res.error() != null) {
                    log.warn("数据治理规则执行异常 ruleId={} name={} : {}", r.getId(), r.getName(), res.error());
                    continue;
                }
                log.info("数据治理规则扫描 ruleId={} 扫描{} 违规{} 通过率{}", r.getId(),
                        res.scanned(), res.errorCount(), res.passRate());
            } catch (Exception ex) {
                // 单条规则异常不影响同批其余规则
                log.warn("数据治理规则扫描兜底异常 ruleId={}: {}", r.getId(), ex.getMessage());
            }
        }
    }
}
