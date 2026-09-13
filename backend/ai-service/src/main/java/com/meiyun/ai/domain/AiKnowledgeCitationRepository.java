package com.meiyun.ai.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AiKnowledgeCitationRepository extends JpaRepository<AiKnowledgeCitation, Long> {

    Page<AiKnowledgeCitation> findByDocIdOrderByCitationIdDesc(Long docId, Pageable pageable);

    long countByDocId(Long docId);

    long countByCreatedAtGreaterThanEqual(OffsetDateTime since);

    long countByUseful(Boolean useful);

    long countByUsefulNotNull();

    /** 热搜词：近 since 起按 query 聚合取前 limit（排除空白）。 */
    @Query(value = """
            select query, count(*) as cnt
            from ai_knowledge_citation
            where created_at >= :since
            group by query
            order by cnt desc, max(created_at) desc
            limit :limit
            """, nativeQuery = true)
    List<Object[]> hotQueries(@Param("since") OffsetDateTime since, @Param("limit") int limit);
}
