package com.meiyun.customer;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 客户域资金事件定时投递（B4 充值储值，outbox 至少一次投递，与 txn FinanceEventRetryJob 同构）。
 *
 * <p>每 30 秒扫描最早 50 条 PENDING 事件（FIFO 限批防长事务），POST finance
 * {@code /api/finance/internal/entries}（X-Internal-Token 系统身份）。finance 侧 idem_key 幂等，
 * 重复投递不双算：
 * <ul>
 *   <li>成功（含幂等重复受理）→ SENT，记 sentAt、清 lastError；</li>
 *   <li>finance 4xx（确定性拒绝，如分录校验不过）→ DEAD，不再重试，留待财务对账人工发现处理
 *       （毒消息不阻塞后续事件）；</li>
 *   <li>连接失败 / 5xx / 超时 → retryCount++、记 lastError、log.warn，下次扫描继续重试，
 *       不回滚、不阻塞业务（业务事务只落事件，REST 调用全部发生在本任务里）。</li>
 * </ul>
 */
@Component
public class CardFinanceEventRetryJob {

    private static final Logger log = LoggerFactory.getLogger(CardFinanceEventRetryJob.class);

    /** 单事件最大投递次数：超过后置 DEAD 并告警，防永久毒消息（人工经对账台介入）。 */
    private static final int MAX_RETRY = 20;

    private final CardFinanceEventRepository eventRepo;
    private final RestTemplate restTemplate;

    @Value("${finance.service.url:http://localhost:8087}")
    private String financeBaseUrl;

    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public CardFinanceEventRetryJob(CardFinanceEventRepository eventRepo, RestTemplate restTemplate) {
        this.eventRepo = eventRepo;
        this.restTemplate = restTemplate;
    }

    @Scheduled(fixedDelay = 30_000L, initialDelay = 10_000L)
    public void dispatchPending() {
        List<CardFinanceEvent> pending = eventRepo.findFirst50ByStatusOrderByEventIdAsc("PENDING");
        if (pending.isEmpty()) return;
        for (CardFinanceEvent e : pending) {
            try {
                dispatchOne(e);
            } catch (Exception ex) {
                // 单条异常不影响同批其余事件
                log.warn("充值资金事件投递兜底异常 eventId={} bizRef={}: {}",
                        e.getEventId(), e.getBizRef(), ex.getMessage());
            }
        }
    }

    /**
     * 投递单条事件并回写状态。单事件仅一次状态 save（Spring Data 仓库方法自带事务），
     * 故不在此加方法级 @Transactional——同类自调用不经代理，且避免整批 50 条共事务相互回滚。
     */
    private void dispatchOne(CardFinanceEvent e) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(e.getPayload(), headers);
        try {
            restTemplate.exchange(financeBaseUrl + "/api/finance/internal/entries",
                    HttpMethod.POST, entity, Object.class);
            e.setStatus("SENT");
            e.setSentAt(OffsetDateTime.now());
            e.setLastError(null);
            eventRepo.save(e);
            log.info("充值资金事件投递成功 eventId={} type={} bizRef={} 重试{}次",
                    e.getEventId(), e.getEventType(), e.getBizRef(), e.getRetryCount());
        } catch (HttpClientErrorException ex) {
            // 4xx：finance 确定性拒绝（分录校验不合法/无权限），重试无意义 → DEAD 待人工
            markDead(e, "finance 拒绝(" + ex.getStatusCode().value() + "): " + clip(ex.getResponseBodyAsString()));
        } catch (Exception ex) {
            // 连接失败 / 5xx / 超时：可恢复，计数重试
            e.setRetryCount(e.getRetryCount() + 1);
            e.setLastError(clip(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            if (e.getRetryCount() >= MAX_RETRY) {
                markDead(e, "重试 " + e.getRetryCount() + " 次仍失败，停止自动投递：" + clip(ex.getMessage()));
            } else {
                eventRepo.save(e);
                log.warn("充值资金事件投递失败，待重试 eventId={} bizRef={} 第{}次: {}",
                        e.getEventId(), e.getBizRef(), e.getRetryCount(), ex.getMessage());
            }
        }
    }

    private void markDead(CardFinanceEvent e, String reason) {
        e.setStatus("DEAD");
        e.setLastError(clip(reason));
        eventRepo.save(e);
        log.error("充值资金事件置 DEAD（需财务对账人工介入）eventId={} type={} bizRef={} reason={}",
                e.getEventId(), e.getEventType(), e.getBizRef(), reason);
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
