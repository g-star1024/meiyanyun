package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {

    /** 工作台会话列表：最近活跃在前（updated_at 与末条消息同步）。 */
    List<AiChatSession> findTop100ByOrderBySessionIdDesc();

    long countByCreatedAtGreaterThanEqual(OffsetDateTime after);

    long countByCreatedAtBetween(OffsetDateTime start, OffsetDateTime end);

    long countByTransferredTrueAndCreatedAtGreaterThanEqual(OffsetDateTime after);
}
