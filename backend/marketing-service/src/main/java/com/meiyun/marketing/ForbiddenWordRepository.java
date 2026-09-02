package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ForbiddenWordRepository extends JpaRepository<ForbiddenWord, Long> {

    /** 管理端列表：按类别、词排序，停用词一并返回。 */
    List<ForbiddenWord> findAllByOrderByCategoryAscWordIdAsc();

    /** 校验用：仅启用词，按类别排序保持命中输出顺序稳定。 */
    List<ForbiddenWord> findByEnabledTrueOrderByCategoryAscWordIdAsc();

    Optional<ForbiddenWord> findByCategoryAndWord(String category, String word);

    long countByEnabledTrue();
}
