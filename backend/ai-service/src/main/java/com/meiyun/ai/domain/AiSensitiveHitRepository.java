package com.meiyun.ai.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AiSensitiveHitRepository extends JpaRepository<AiSensitiveHit, Long> {

    Page<AiSensitiveHit> findAllByOrderByHitIdDesc(Pageable pageable);

    Page<AiSensitiveHit> findByCategoryOrderByHitIdDesc(String category, Pageable pageable);

    long countByHitAtGreaterThanEqual(OffsetDateTime since);

    long countByFalsePositiveTrue();

    /** 按词聚合成对：word_id（0 表示词已被删的孤儿命中）、命中次数、最近命中时间。 */
    @Query("""
            select h.wordId, count(h.hitId), max(h.hitAt)
            from AiSensitiveHit h
            group by h.wordId
            """)
    List<Object[]> countGroupByWord();

    @Query("select count(h.hitId) from AiSensitiveHit h where h.wordId = :wordId")
    long countByWordId(@Param("wordId") Long wordId);
}
