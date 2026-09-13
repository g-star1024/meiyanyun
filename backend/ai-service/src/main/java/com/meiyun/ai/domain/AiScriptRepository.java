package com.meiyun.ai.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiScriptRepository extends JpaRepository<AiScript, Long> {

    /** 话术库列表：scene 精确（可空为全部），keyword 模糊匹配标题/内容，按新建倒序。 */
    @Query("""
            select s from AiScript s
            where (:scene = '' or s.scene = :scene)
              and (:keyword = '' or lower(s.title) like lower(concat('%', :keyword, '%'))
                   or lower(s.content) like lower(concat('%', :keyword, '%')))
            order by s.scriptId desc
            """)
    Page<AiScript> search(@Param("scene") String scene,
                          @Param("keyword") String keyword,
                          Pageable pageable);

    long countByAdoptedCountGreaterThan(Long n);

    long countByRatingGreaterThanEqual(Integer rating);
}
