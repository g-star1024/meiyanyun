package com.meiyun.marketing.infra;

import com.meiyun.common.event.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 领域事件发布器 · 日志实现（联调期默认）：把事件打印到日志，不依赖 MQ。
 *
 * <p>始终注册；生产切 {@code meiyun.event-publisher=mq} 时由 MqDomainEventPublisher
 * 作为 @Primary 接管，本实现保留为降级兜底（MQ 故障时写日志，事件由 Outbox 补偿重放）。
 */
@Component
public class LoggingDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingDomainEventPublisher.class);

    @Override
    public void publish(String topic, String key, String payload) {
        log.info("[DOMAIN-EVENT] topic={} key={} payload={}", topic, key, payload);
    }
}
