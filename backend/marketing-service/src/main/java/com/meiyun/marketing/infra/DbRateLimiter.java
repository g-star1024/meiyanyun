package com.meiyun.marketing.infra;

import com.meiyun.common.ratelimit.RateLimiter;
import com.meiyun.marketing.PushRecordRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 频控 DB 回退实现（联调期默认 / Redis 故障降级）。
 *
 * <p>始终注册为 bean：① 配置 {@code meiyun.rate-limiter=db}（默认）时作为主实现；
 * ② 配置为 redis 时作为 RedisRateLimiter 的故障降级 fallback。
 *
 * <p>按 key 前缀分派：① {@code push:customer:<customerId>}（PushService 触达）查 push_record
 * 表近窗口计数（历史行为不变）；② 其他前缀（public:landing / channel-cb 等免鉴权入口，按 IP 计）
 * 走进程内存固定窗口——单实例降级模式近似计数，多实例部署应切 meiyun.rate-limiter=redis。
 */
@Component
public class DbRateLimiter implements RateLimiter {

    private static final String PUSH_PREFIX = "push:customer:";
    private static final int MEMORY_KEY_GUARD = 10_000;

    private final PushRecordRepository pushRepo;
    private final Map<String, WindowCounter> memoryCounters = new ConcurrentHashMap<>();

    public DbRateLimiter(PushRecordRepository pushRepo) {
        this.pushRepo = pushRepo;
    }

    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        if (key.startsWith(PUSH_PREFIX)) {
            return currentCount(key, windowSeconds) < limit;
        }
        long now = OffsetDateTime.now().toEpochSecond();
        if (memoryCounters.size() > MEMORY_KEY_GUARD) {
            memoryCounters.entrySet().removeIf(e -> now - e.getValue().windowStartEpochSecond >= windowSeconds);
        }
        WindowCounter counter = memoryCounters.computeIfAbsent(key, k -> new WindowCounter(now));
        synchronized (counter) {
            if (now - counter.windowStartEpochSecond >= windowSeconds) {
                counter.windowStartEpochSecond = now;
                counter.count.set(0);
            }
            return counter.count.incrementAndGet() <= limit;
        }
    }

    @Override
    public long currentCount(String key, int windowSeconds) {
        if (!key.startsWith(PUSH_PREFIX)) {
            long now = OffsetDateTime.now().toEpochSecond();
            WindowCounter counter = memoryCounters.get(key);
            if (counter == null || now - counter.windowStartEpochSecond >= windowSeconds) {
                return 0L;
            }
            return counter.count.get();
        }
        String customerId = key.substring(PUSH_PREFIX.length());
        return pushRepo.countByCustomerSince(customerId,
                OffsetDateTime.now().minusSeconds(windowSeconds));
    }

    private static final class WindowCounter {
        private volatile long windowStartEpochSecond;
        private final AtomicInteger count = new AtomicInteger(0);

        private WindowCounter(long windowStartEpochSecond) {
            this.windowStartEpochSecond = windowStartEpochSecond;
        }
    }
}
