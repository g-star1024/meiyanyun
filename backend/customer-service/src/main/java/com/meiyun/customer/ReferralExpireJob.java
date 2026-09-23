package com.meiyun.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 转介绍到期批处理（P5-B86 卡2，DESIGN-P5-B86 §4.4，对齐 GrantExpireJob 范式）。
 * 每日 03:30（Asia/Shanghai，cron 可外部化；错开赠金过期 03:00）扫描 PENDING/CONFIRMED 且
 * expire_at 已过的转介绍单，逐条置 EXPIRED、写审计（REFERRAL/EXPIRE）。
 *
 * <p>大事务毒化防护（复刻 GrantExpireJob 范式）：
 * <ul>
 *   <li>Job 方法本身无 {@code @Transactional}，扫描限批（每批 {@value #BATCH_SIZE} 条，单次最多
 *       {@value #MAX_BATCHES} 批），过期动作走 {@link ReferralService#expireOne} 单条独立事务；</li>
 *   <li>单条失败 try-catch 不中断同批（坏数据不毒化其余单），失败计数上报告警；</li>
 *   <li>每轮都取第 0 页——成功行已翻成 EXPIRED 自然退出结果集；失败行保留原态，
 *       靠 MAX_BATCHES 上限防「满批皆失败」死循环，留待下轮/人工。</li>
 * </ul>
 */
@Component
public class ReferralExpireJob {

    private static final Logger log = LoggerFactory.getLogger(ReferralExpireJob.class);

    static final int BATCH_SIZE = 200;
    static final int MAX_BATCHES = 50;

    private final ReferralService referralService;
    private final ReferralRepository referralRepo;

    public ReferralExpireJob(ReferralService referralService, ReferralRepository referralRepo) {
        this.referralService = referralService;
        this.referralRepo = referralRepo;
    }

    @Scheduled(cron = "${meiyun.referral-expire.cron:0 30 3 * * *}", zone = "Asia/Shanghai")
    public void expire() {
        OffsetDateTime now = OffsetDateTime.now();
        int expired = 0;
        int failed = 0;
        for (int page = 0; page < MAX_BATCHES; page++) {
            List<Referral> batch;
            try {
                batch = referralRepo.findByStatusInAndExpireAtBeforeOrderByExpireAtAsc(
                        ReferralService.EXPIRABLE_STATUSES, now, PageRequest.of(0, BATCH_SIZE));
            } catch (Exception ex) {
                log.warn("转介绍到期扫描异常，本轮中止（已处理 {} 条）：{}", expired, ex.getMessage());
                break;
            }
            if (batch.isEmpty()) break;
            for (Referral r : batch) {
                try {
                    if (referralService.expireOne(r.getReferralId(), now)) {
                        expired++;
                    }
                } catch (Exception ex) {
                    failed++;
                    log.warn("转介绍到期单条失败 referralId={} refereeCustomerId={}: {}",
                            r.getReferralId(), r.getRefereeCustomerId(), ex.getMessage());
                }
            }
            if (batch.size() < BATCH_SIZE) break;
        }
        if (expired > 0 || failed > 0) {
            log.info("转介绍到期批处理完成：过期 {} 条，失败 {} 条", expired, failed);
        }
    }
}
