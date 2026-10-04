package com.meiyun.marketing.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.common.event.DomainEventPublisher;
import com.meiyun.marketing.IntegrationConfigClient;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 领域事件发布器 · Kafka 实现（棒⑧卡5 装配位）：{@code meiyun.event-publisher=mq} 时注册并
 * 作为 @Primary 接管 LoggingDomainEventPublisher。
 *
 * <p>开关裁决：org 集成目录 KAFKA_DIRECT（SWITCH）未启用 → 即使 mq 模式也如实降级 Logging
 * 口径（同格式落日志注明降级原因），不伪造已发送；启用 → 解析 config_json 两字段
 * （bootstrapServers/clientIdPrefix）＋KAFKA_SECRET（SASL JAAS 配置串，PLAINTEXT 内网直连留空），
 * 任一缺失或非法 → SKIPPED 诚实降级如实落日志。KafkaProducer 懒装配，config hash 变化即重建；
 * env（MEIYUN_KAFKA_DIRECT_*）为库外兜底。
 *
 * <p>守 DomainEventPublisher 设计原则：发送失败仅 warn 落日志不抛异常，事件由 Outbox 兜底
 * 补偿重放，绝不影响主业务事务。
 *
 * <p>边界：本类＝事件发布装配位；Debezium/Canal CDC 引擎与真实 Kafka broker 基建仍属数据中台
 * 二期（DESIGN-T3 §7 口径）。
 */
@Component
@Primary
@ConditionalOnProperty(name = "meiyun.event-publisher", havingValue = "mq")
public class KafkaDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaDomainEventPublisher.class);

    private static final String CODE_SWITCH = "KAFKA_DIRECT";
    private static final String CODE_SECRET = "KAFKA_SECRET";
    private static final long SEND_TIMEOUT_MILLIS = 5_000L;

    private final IntegrationConfigClient configClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${meiyun.kafka.direct.enabled:false}")
    private boolean envEnabled;
    @Value("${meiyun.kafka.direct.config:}")
    private String envConfig;
    @Value("${meiyun.kafka.direct.secret:}")
    private String envSecret;

    private volatile String producerHash;
    private volatile KafkaProducer<String, String> producer;

    public KafkaDomainEventPublisher(IntegrationConfigClient configClient) {
        this.configClient = configClient;
    }

    @Override
    public void publish(String topic, String key, String payload) {
        if (!configClient.resolveSwitch(CODE_SWITCH, envEnabled)) {
            log.info("[DOMAIN-EVENT] topic={} key={} payload={}（KAFKA_DIRECT 未启用，如实降级 Logging 口径）",
                    topic, key, payload);
            return;
        }
        KafkaProducer<String, String> p = producerOrNull();
        if (p == null) {
            return;
        }
        try {
            p.send(new ProducerRecord<>(topic, key, payload)).get(SEND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Kafka 事件发送失败（SKIPPED 诚实降级，事件由 Outbox 兜底重放）topic={} key={} 原因：{}",
                    topic, key, e.getMessage());
        }
    }

    /** 懒装配 KafkaProducer：config hash 变化即重建；配置缺失/非法 → null（SKIPPED 如实落日志）。 */
    private KafkaProducer<String, String> producerOrNull() {
        KafkaConfig cfg = parseConfig(configClient.resolveConfigJson(CODE_SWITCH, envConfig));
        if (cfg == null) {
            return null;
        }
        String secret = configClient.resolveSecret(CODE_SECRET, envSecret);
        String hash = sha256(cfg.bootstrapServers() + "|" + cfg.clientIdPrefix() + "|" + (secret == null ? "" : secret));
        if (producer == null || !hash.equals(producerHash)) {
            synchronized (this) {
                if (producer == null || !hash.equals(producerHash)) {
                    KafkaProducer<String, String> old = producer;
                    producer = buildProducer(cfg, secret);
                    producerHash = hash;
                    if (old != null) {
                        old.close(Duration.ofSeconds(2));
                    }
                    log.info("Kafka 事件发布 producer 已装配 bootstrapServers={} clientIdPrefix={} sasl={}",
                            cfg.bootstrapServers(), cfg.clientIdPrefix(), secret == null || secret.isBlank() ? "无" : "有");
                }
            }
        }
        return producer;
    }

    private KafkaProducer<String, String> buildProducer(KafkaConfig cfg, String secret) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.bootstrapServers());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, cfg.clientIdPrefix() + "-" + UUID.randomUUID().toString().substring(0, 8));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 2);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        if (secret != null && !secret.isBlank()) {
            props.put("security.protocol", "SASL_PLAINTEXT");
            props.put("sasl.mechanism", "SCRAM-SHA-512");
            props.put("sasl.jaas.config", secret);
        }
        return new KafkaProducer<>(props);
    }

    private KafkaConfig parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            log.warn("Kafka 事件发布 SKIPPED：KAFKA_DIRECT 扩展参数未配置（bootstrapServers/clientIdPrefix）");
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(configJson);
        } catch (Exception e) {
            log.warn("Kafka 事件发布 SKIPPED：KAFKA_DIRECT 扩展参数不是合法 JSON");
            return null;
        }
        String bootstrapServers = text(node, "bootstrapServers");
        String clientIdPrefix = text(node, "clientIdPrefix");
        if (bootstrapServers == null) {
            log.warn("Kafka 事件发布 SKIPPED：KAFKA_DIRECT 扩展参数 bootstrapServers 未配置");
            return null;
        }
        if (clientIdPrefix == null) {
            log.warn("Kafka 事件发布 SKIPPED：KAFKA_DIRECT 扩展参数 clientIdPrefix 未配置");
            return null;
        }
        return new KafkaConfig(bootstrapServers, clientIdPrefix);
    }

    @PreDestroy
    public void close() {
        KafkaProducer<String, String> p = producer;
        if (p != null) {
            p.close(Duration.ofSeconds(2));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        return v.asText();
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record KafkaConfig(String bootstrapServers, String clientIdPrefix) {
    }
}
