package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 违禁词库（A1-04 服务化）：DB 存储 + Redis 缓存 + 管理端维护。
 * 替代原 {@link ForbiddenWords} 纯静态词库；启动时由 {@link ForbiddenWordDataInitializer}
 * 幂等播种四类默认词（绝对化用语/医疗承诺/虚假宣传/低俗诱导）。
 */
@Entity
@Table(name = "forbidden_word",
        uniqueConstraints = @UniqueConstraint(name = "uk_forbidden_word_category_word",
                columnNames = {"category", "word"}))
@Getter @Setter @NoArgsConstructor
public class ForbiddenWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "word_id")
    private Long wordId;

    /** 词库类别：绝对化用语 / 医疗承诺 / 虚假宣传 / 低俗诱导。 */
    @Column(nullable = false, length = 32)
    private String category;

    /** 违禁词本体（同类别内唯一）。 */
    @Column(nullable = false, length = 64)
    private String word;

    /** 是否启用：false 表示停用但保留审计痕迹（不参与校验）。 */
    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
