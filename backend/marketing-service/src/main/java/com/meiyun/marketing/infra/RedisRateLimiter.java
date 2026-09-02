package com.meiyun.marketing.infra;

import com.meiyun.common.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 频控 Redis 实现（生产）：用 INCR + EXPIRE 原子计数，多实例共享、高性能。
 *
 * <p>启用条件：{@code meiyun.rate-limiter=redis}。
 * key 形如 {@code meiyun:rl:push:customer:M001}，TTL = 窗口长度。
 *
 * <p>容错：若 Redis 连接异常（生产故障/未启动），记录告警并回退到 DB 计数检查，
 * 保证触达链路不因中间件抖动而整体不可用（降级而非雪崩）。
 */
@Component
@Primary
@ConditionalOnProperty(name = "meiyun.rate-limiter", havingValue = "redis")
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String KEY_PREFIX = "meiyun:rl:";

    private final StringRedisTemplate redis;
    private final DbRateLimiter fallback;

    public RedisRateLimiter(StringRedisTemplate redis, DbRateLimiter fallback) {
        this.redis = redis;
        this.fallback = fallback;
    }

    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        String rk = KEY_PREFIX + key;
        try {
            Long count = redis.opsForValue().increment(rk);
            if (count != null && count == 1L) {
                redis.expire(rk, Duration.ofSeconds(windowSeconds));
            }
            return count != null && count <= limit;
        } catch (Exception e) {
            log.warn("Redis 频控失败，回退 DB 计数检查：key={} err={}", rk, e.getMessage());
            return fallback.tryAcquire(key, limit, windowSeconds);
        }
    }

    @Override
    public long currentCount(String key, int windowSeconds) {
        try {
            String v = redis.opsForValue().get(KEY_PREFIX + key);
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            log.warn("Redis 读取计数失败，回退 DB：key={} err={}", key, e.getMessage());
            return fallback.currentCount(key, windowSeconds);
        }
    }
}
