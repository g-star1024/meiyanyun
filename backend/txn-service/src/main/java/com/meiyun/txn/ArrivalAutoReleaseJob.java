package com.meiyun.txn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 候诊超时自动释放号源（P5-B29 卡②）。
 *
 * <p>复刻 {@link ApprovalSlaJob} 范式：{@code @Scheduled} 限批 50 条 FIFO、单条 try-catch、
 * 扫描方法不加方法级事务（每条经 {@link ArrivalService} 代理的 @Transactional 独立短事务）、SLF4J。
 * 每分钟一轮：
 * <ol>
 *   <li>cutoff = now -（waitingTimeoutMin + releaseGraceMin），取最早 50 条 arrivedAt 早于 cutoff 的 WAITING；</li>
 *   <li>逐条调 {@code arrivalService.releaseTimeoutBySystem(ahNo)}：事务内重载状态，WAITING → LEFT（leftAt 落库）
 *       并同事务触发本店候补首位递补通知；记录已消失/已非 WAITING（崩溃重入、手工抢先）返回 null 计跳过；</li>
 *   <li>单条异常仅告警不中断整批，下轮自愈。</li>
 * </ol>
 *
 * <p>阈值默认与前端 settings 对齐：候诊超时 30 分钟、释放宽限 10 分钟（前端「释放中」口径同为 timeout+grace）；
 * {@code meiyun.queue.auto-release-enabled} 总开关对齐前端 autoReleaseSlot，关闭时仅扫描不释放。
 */
@Component
public class ArrivalAutoReleaseJob {

    private static final Logger log = LoggerFactory.getLogger(ArrivalAutoReleaseJob.class);

    private final ArrivalRepository arrivalRepo;
    private final ArrivalService arrivalService;

    /** 自动释放总开关（对齐前端 autoReleaseSlot），关闭时本轮不释放。 */
    @org.springframework.beans.factory.annotation.Value("${meiyun.queue.auto-release-enabled:true}")
    private boolean autoReleaseEnabled;
    /** 候诊超时阈值（分钟），与前端 waitingTimeoutMin 同默认。 */
    @org.springframework.beans.factory.annotation.Value("${meiyun.queue.waiting-timeout-min:30}")
    private long waitingTimeoutMin;
    /** 释放宽限期（分钟），与前端 releaseGraceMin 同默认；到点 = 超时 + 宽限。 */
    @org.springframework.beans.factory.annotation.Value("${meiyun.queue.release-grace-min:10}")
    private long releaseGraceMin;

    public ArrivalAutoReleaseJob(ArrivalRepository arrivalRepo, ArrivalService arrivalService) {
        this.arrivalRepo = arrivalRepo;
        this.arrivalService = arrivalService;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 20_000L)
    public void scanTimeout() {
        if (!autoReleaseEnabled) {
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now()
                .minusMinutes(waitingTimeoutMin + releaseGraceMin);
        List<Arrival> candidates = arrivalRepo
                .findFirst50ByStatusAndArrivedAtBeforeOrderByArrivedAtAsc(
                        ArrivalService.ST_WAITING, cutoff);
        if (candidates.isEmpty()) {
            return;
        }
        int released = 0;
        int skipped = 0;
        for (Arrival a : candidates) {
            try {
                if (arrivalService.releaseTimeoutBySystem(a.getAhNo()) == null) {
                    skipped++;
                } else {
                    released++;
                }
            } catch (Exception ex) {
                // 单条异常不影响同批其余记录，下轮自愈
                log.warn("候诊超时自动释放兜底异常 ahNo={}: {}", a.getAhNo(), ex.getMessage());
            }
        }
        log.info("候诊超时自动释放本轮完成 候选={} 释放={} 跳过={} cutoff={}",
                candidates.size(), released, skipped, cutoff);
    }
}
