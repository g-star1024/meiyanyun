package com.meiyun.ai.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AiKnowledgeItemRepository extends JpaRepository<AiKnowledgeItem, Long> {

    /**
     * 知识库列表：category 精确（空串=全部），keyword 空串归一化后模糊标题/标签/正文。
     * 仅 INDEXED 条目可被关键词检索；keyword 为空（普通浏览）时返回全部状态。
     */
    @Query("""
            select d from AiKnowledgeItem d
            where (:category = '' or d.category = :category)
              and (:keyword = '' or (d.indexStatus = 'INDEXED'
                   and (lower(d.title) like lower(concat('%', :keyword, '%'))
                     or lower(coalesce(d.tags, '')) like lower(concat('%', :keyword, '%'))
                     or lower(d.content) like lower(concat('%', :keyword, '%')))))
            order by d.docId desc
            """)
    Page<AiKnowledgeItem> search(@Param("category") String category,
                                 @Param("keyword") String keyword,
                                 Pageable pageable);

    /**
     * 检索召回：仅 INDEXED 条目，标题命中权重最高、标签次之、正文最低，
     * 同权重按引用次数与 docId 倒序。中文短查询走 PG ILIKE 词法匹配（不引入 pg_trgm/zhparser 扩展）。
     */
    @Query(value = """
            select * from ai_knowledge_item
            where index_status = 'INDEXED'
              and (lower(title) like lower(concat('%', cast(:keyword as text), '%'))
                or lower(coalesce(tags, '')) like lower(concat('%', cast(:keyword as text), '%'))
                or lower(content) like lower(concat('%', cast(:keyword as text), '%')))
            order by
              case
                when lower(title) like lower(concat('%', cast(:keyword as text), '%')) then 0
                when lower(coalesce(tags, '')) like lower(concat('%', cast(:keyword as text), '%')) then 1
                else 2
              end,
              refs_count desc, doc_id desc
            limit :limit
            """, nativeQuery = true)
    List<AiKnowledgeItem> recall(@Param("keyword") String keyword, @Param("limit") int limit);

    long countByIndexStatus(String indexStatus);
}
