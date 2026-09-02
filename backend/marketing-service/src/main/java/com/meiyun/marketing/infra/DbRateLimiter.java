package com.meiyun.marketing.infra;

import com.meiyun.common.ratelimit.RateLimiter;
import com.meiyun.marketing.PushRecordRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 频控 DB 回退实现（联调期默认 / Redis 故障降级）：直接查 push_record 表近窗口计数。
 *
 * <p>始终注册为 bean：① 配置 {@code meiyun.rate-limiter=db}（默认）时作为主实现；
 * ② 配置为 redis 时作为 RedisRateLimiter 的故障降级 fallback。
 *
 * <p>key 约定格式：{@code push:customer:<customerId>}，仅取末段 customerId。
 */
@Component
public class DbRateLimiter implements RateLimiter {

    private final PushRecordRepository pushRepo;

    public DbRateLimiter(PushRecordRepository pushRepo) {
        this.pushRepo = pushRepo;
    }

    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        return currentCount(key, windowSeconds) < limit;
    }

    @Override
    public long currentCount(String key, int windowSeconds) {
        String customerId = key.contains(":") ? key.substring(key.lastIndexOf(':') + 1) : key;
        return pushRepo.countByCustomerSince(customerId,
                OffsetDateTime.now().minusSeconds(windowSeconds));
    }
}
