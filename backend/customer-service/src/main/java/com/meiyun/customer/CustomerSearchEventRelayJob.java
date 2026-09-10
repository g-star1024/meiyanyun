package com.meiyun.customer;

import com.meiyun.customer.search.CustomerSearchService;
import com.meiyun.customer.search.CustomerSearchService.UpsertResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 客户检索事件近线中继（B30 建档实时写 ES，与 CardFinanceEventRetryJob 同构的 outbox 中继）。
 *
 * <p>每 10 秒扫描最早 50 条 PENDING 事件（FIFO 限批防长事务）；事件只存 customerId，
 * 投递时回查 PG 取权威客户数据，PUT ES {@code meiyun-customer/_doc/{customerId}} 幂等 upsert，
 * 重复投递覆盖同一文档不双算：
 * <ul>
 *   <li>SENT（ES 2xx，含重复覆盖）→ 置 SENT，记 sentAt、清 lastError；</li>
 *   <li>RETRY（连接失败/5xx/429/熔断开路/索引未就绪）→ retryCount++、记 lastError、log.warn，
 *       下次扫描继续重试，不回滚、不阻塞建档（建档事务只落事件，ES 调用全部发生在本任务里）；</li>
 *   <li>DEAD（ES 4xx 确定性拒绝，或客户已不存在）→ 不再重试，留待人工排查（毒消息不阻塞后续事件）。</li>
 * </ul>
 *
 * <p>initialDelay 15 秒：让 {@code ApplicationReadyEvent} 的索引探测/建索引+全量回填先跑完。
 */
@Component
public class CustomerSearchEventRelayJob {

    private static final Logger log = LoggerFactory.getLogger(CustomerSearchEventRelayJob.class);

    /** 单事件最大投递次数：超过后置 DEAD 并告警，防永久毒消息。 */
    private static final int MAX_RETRY = 20;

    private final CustomerSearchEventRepository eventRepo;
    private final CustomerRepository customerRepo;
    private final CustomerSearchService searchService;

    public CustomerSearchEventRelayJob(CustomerSearchEventRepository eventRepo,
                                       CustomerRepository customerRepo,
                                       CustomerSearchService searchService) {
        this.eventRepo = eventRepo;
        this.customerRepo = customerRepo;
        this.searchService = searchService;
    }

    @Scheduled(fixedDelay = 10_000L, initialDelay = 15_000L)
    public void dispatchPending() {
        List<CustomerSearchEvent> pending = eventRepo.findFirst50ByStatusOrderByEventIdAsc("PENDING");
        if (pending.isEmpty()) return;
        for (CustomerSearchEvent e : pending) {
            try {
                dispatchOne(e);
            } catch (Exception ex) {
                // 单条异常不影响同批其余事件
                log.warn("客户检索事件中继兜底异常 eventId={} customerId={}: {}",
                        e.getEventId(), e.getCustomerId(), ex.getMessage());
            }
        }
    }

    /**
     * 回查 PG 后投递单条事件并回写状态。单事件仅一次状态 save（Spring Data 仓库方法自带事务），
     * 故不在此加方法级 @Transactional——避免整批 50 条共事务相互回滚。
     */
    private void dispatchOne(CustomerSearchEvent e) {
        Optional<Customer> customer = customerRepo.findById(e.getCustomerId());
        if (customer.isEmpty()) {
            markDead(e, "客户不存在：" + e.getCustomerId());
            return;
        }
        UpsertResult result = searchService.upsert(customer.get());
        switch (result) {
            case SENT -> {
                e.setStatus("SENT");
                e.setSentAt(OffsetDateTime.now());
                e.setLastError(null);
                eventRepo.save(e);
                log.info("客户检索事件投递成功 eventId={} customerId={} 重试{}次",
                        e.getEventId(), e.getCustomerId(), e.getRetryCount());
            }
            case DEAD -> markDead(e, "ES 确定性拒绝（4xx），停止自动投递");
            case RETRY -> {
                e.setRetryCount(e.getRetryCount() + 1);
                e.setLastError(clip("ES 暂不可用，等待下次中继"));
                if (e.getRetryCount() >= MAX_RETRY) {
                    markDead(e, "重试 " + e.getRetryCount() + " 次仍失败，停止自动投递");
                } else {
                    eventRepo.save(e);
                    log.warn("客户检索事件投递失败，待重试 eventId={} customerId={} 第{}次",
                            e.getEventId(), e.getCustomerId(), e.getRetryCount());
                }
            }
        }
    }

    private void markDead(CustomerSearchEvent e, String reason) {
        e.setStatus("DEAD");
        e.setLastError(clip(reason));
        eventRepo.save(e);
        log.error("客户检索事件置 DEAD（需人工排查）eventId={} customerId={} reason={}",
                e.getEventId(), e.getCustomerId(), reason);
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
