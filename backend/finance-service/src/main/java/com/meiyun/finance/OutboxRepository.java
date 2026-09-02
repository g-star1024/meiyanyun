package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxRecord, Long> {

    List<OutboxRecord> findByStatusOrderByCreatedAtDesc(String status);
}
