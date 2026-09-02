package com.meiyun.common.ratelimit;

/**
 * 频控器抽象：在滑动窗口内对某 key 计数，超限拒绝。
 *
 * <p>典型实现：① 基于 DB count 的本地回退实现（联调期默认，不依赖中间件）；
 * ② 基于 Redis 的实现（生产，原子 INCR + EXPIRE，多实例共享）。
 * 通过配置 {@code meiyun.rate-limiter} 切换：db（默认）| redis。
 */
public interface RateLimiter {

    /**
     * 尝试占用一个配额。
     *
     * @param key          频控维度（如 push:customer:M001）
     * @param limit        窗口内上限
     * @param windowSeconds 窗口长度（秒）
     * @return 允许返回 true；已达上限返回 false
     */
    boolean tryAcquire(String key, int limit, int windowSeconds);

    /**
     * 查询当前窗口内已用配额。
     */
    long currentCount(String key, int windowSeconds);
}
