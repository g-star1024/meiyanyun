package com.meiyun.ai.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;

public interface AiContentRecordRepository extends JpaRepository<AiContentRecord, Long> {

    Page<AiContentRecord> findByChannelOrderByRecordIdDesc(String channel, Pageable pageable);

    Page<AiContentRecord> findAllByOrderByRecordIdDesc(Pageable pageable);

    long countByCreatedAtGreaterThanEqual(OffsetDateTime since);

    long countByDeployedAtGreaterThanEqual(OffsetDateTime since);

    long countByStatus(String status);
}
