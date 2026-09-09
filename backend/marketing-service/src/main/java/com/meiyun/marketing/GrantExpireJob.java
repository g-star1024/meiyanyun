package com.meiyun.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 赠金过期批处理（域⑤ 赠金）。每日 03:00（Asia/Shanghai，cron 可外部化）扫描 VALID 且 expire_at 已过
 * 的赠金，逐条置 EXPIRED、余额清零、写审计。
 *
 * <p>大事务毒化防护（复刻 FinanceEventRetryJob/ApprovalSlaJob 范式）：
 * <ul>
 *   <li>Job 方法本身无 {@code @Transactional}，扫描限批（每批 {@value #BATCH_SIZE} 条，单次最多
 *       {@value #MAX_BATCHES} 批），过期动作走 {@link GrantService#expireOne} 单条独立事务；</li>
 *   <li>单条失败 try-catch 不中断同批（坏数据不毒化其余赠金），失败计数上报告警；</li>
 *   <li>每轮都取第 0 页——成功行已翻成 EXPIRED 自然退出结果集；失败行保留 VALID，
 *       靠 MAX_BATCHES 上限防「满批皆失败」死循环，留待下轮/人工。</li>
 * </ul>
 */
@Component
public class GrantExpireJob {

    private static final Logger log = LoggerFactory.getLogger(GrantExpireJob.class);

    static final int BATCH_SIZE = 200;
    static final int MAX_BATCHES = 50;

    private final GrantService grantService;
    private final CustomerGrantRepository grantRepo;

    public GrantExpireJob(GrantService grantService, CustomerGrantRepository grantRepo) {
        this.grantService = grantService;
        this.grantRepo = grantRepo;
    }

    @Scheduled(cron = "${meiyun.grant-expire.cron:0 0 3 * * *}", zone = "Asia/Shanghai")
    public void expire() {
        OffsetDateTime now = OffsetDateTime.now(GrantService.BIZ_ZONE);
        int expired = 0;
        int failed = 0;
        for (int page = 0; page < MAX_BATCHES; page++) {
            List<CustomerGrant> batch;
            try {
                batch = grantRepo.findByStatusAndExpireAtBefore(
                        "VALID", now, PageRequest.of(0, BATCH_SIZE));
            } catch (Exception ex) {
                log.warn("赠金过期扫描异常，本轮中止（已处理 {} 条）：{}", expired, ex.getMessage());
                break;
            }
            if (batch.isEmpty()) break;
            for (CustomerGrant g : batch) {
                try {
                    if (grantService.expireOne(g.getId(), now)) {
                        expired++;
                    }
                } catch (Exception ex) {
                    failed++;
                    log.warn("赠金过期单条失败 grantId={} customerId={}: {}",
                            g.getId(), g.getCustomerId(), ex.getMessage());
                }
            }
            if (batch.size() < BATCH_SIZE) break;
        }
        if (expired > 0 || failed > 0) {
            log.info("赠金过期批处理完成：过期 {} 条，失败 {} 条", expired, failed);
        }
    }
}
