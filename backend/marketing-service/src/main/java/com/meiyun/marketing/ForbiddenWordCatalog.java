package com.meiyun.marketing;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 违禁词库只读视图（带缓存）。
 *
 * <p>独立成 Bean 的原因：Spring Cache 基于代理生效，{@link ForbiddenWordService#check(String)}
 * 内部同类自调用不会走缓存；把 @Cacheable 读取放在独立组件，check/预检均命中缓存。
 *
 * <p>缓存名 {@code forbiddenWords}：有 Redis 依赖时由 RedisCacheManager 承载（多实例共享），
 * 无 Redis 时自动回退 ConcurrentMapCacheManager（本地内存）。词库变更后由 Service 写操作
 * 统一 {@code @CacheEvict(allEntries=true)} 失效。
 */
@Component
public class ForbiddenWordCatalog {

    public static final String CACHE_NAME = "forbiddenWords";

    private final ForbiddenWordRepository repo;

    public ForbiddenWordCatalog(ForbiddenWordRepository repo) {
        this.repo = repo;
    }

    /** 启用词按类别分组（LinkedHashMap 保类别顺序），供发送前校验与管理端展示。 */
    @Cacheable(cacheNames = CACHE_NAME, key = "'enabled-categories'")
    @Transactional(readOnly = true)
    public Map<String, List<String>> enabledCategories() {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (ForbiddenWord w : repo.findByEnabledTrueOrderByCategoryAscWordIdAsc()) {
            grouped.computeIfAbsent(w.getCategory(), k -> new java.util.ArrayList<>()).add(w.getWord());
        }
        return grouped;
    }

    /**
     * 全量词（含停用）只读视图，管理端维护列表用。
     *
     * <p>事务内把实体装配为 {@link ForbiddenWordView} 再出缓存：JPA 实体不参与
     * Redis 序列化，缓存中存的是可安全共享的不可变视图。
     */
    @Cacheable(cacheNames = CACHE_NAME, key = "'all-list'")
    @Transactional(readOnly = true)
    public List<ForbiddenWordView> all() {
        return repo.findAllByOrderByCategoryAscWordIdAsc().stream()
                .map(ForbiddenWordView::of)
                .toList();
    }
}
