package com.meiyun.common.event;

/**
 * 领域事件发布器抽象：业务服务在关键动作（下单/支付/兑换/触达等）完成后发布领域事件，
 * 由基础设施异步投递到 MQ（RocketMQ/Kafka），供下游（审计、数仓、通知）消费。
 *
 * <p>实现：① LoggingDomainEventPublisher（默认，仅写日志，联调期不依赖 MQ）；
 * ② MqDomainEventPublisher（生产，投递到 MQ broker）。
 * 通过配置 {@code meiyun.event-publisher} 切换：log（默认）| mq。
 *
 * <p>设计原则：发布失败不得影响主业务事务（Outbox 模式兜底），故不抛受检异常。
 */
public interface DomainEventPublisher {

    /**
     * 发布一个领域事件。
     *
     * @param topic   主题（如 meiyun.txn.order-created）
     * @param key     业务键（如订单号/客户号，用于 MQ 分区路由）
     * @param payload 事件体（JSON 字符串）
     */
    void publish(String topic, String key, String payload);
}
