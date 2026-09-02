package com.meiyun.marketing;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 违禁词只读视图：缓存与管理端列表的载体（A1-04）。
 *
 * <p>不直接缓存 JPA 实体 {@link ForbiddenWord} 的原因：
 * <ul>
 *   <li>JDK 序列化（RedisCacheManager 默认）要求实体实现 Serializable，JPA 实体游离后
 *       脱离持久化上下文，缓存反序列化得到的是游离对象，语义不清；</li>
 *   <li>record 视图字段固定、天然不可变，可安全放入 Redis（多实例共享）。</li>
 * </ul>
 *
 * <p>JSON 字段名与实体一致（wordId/category/word/enabled/createdAt/updatedAt），前端无感知。
 */
public record ForbiddenWordView(
        Long wordId,
        String category,
        String word,
        Boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) implements Serializable {

    /** 由实体装配只读视图。 */
    public static ForbiddenWordView of(ForbiddenWord w) {
        return new ForbiddenWordView(w.getWordId(), w.getCategory(), w.getWord(),
                w.getEnabled(), w.getCreatedAt(), w.getUpdatedAt());
    }
}
