package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    List<AiChatMessage> findBySessionIdOrderByMessageIdAsc(Long sessionId);

    long countBySenderAndCreatedAtGreaterThanEqual(String sender, OffsetDateTime after);

    long countByCreatedAtGreaterThanEqual(OffsetDateTime after);

    long countByCreatedAtBetween(OffsetDateTime start, OffsetDateTime end);

    /** 指定时间起 AI 回复平均出站耗时（ms），无 AI 回复返回 null。 */
    @Query("""
            select avg(m.latencyMs) from AiChatMessage m
            where m.sender = 'ai' and m.latencyMs is not null and m.createdAt >= :since
            """)
    Double avgAiLatencySince(@Param("since") OffsetDateTime since);
}
