package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiT4AlertEventRepository extends JpaRepository<AiT4AlertEvent, Long> {

    Optional<AiT4AlertEvent> findByCode(String code);

    boolean existsByCode(String code);

    /** 事件列表全量直返（量级小，前端 events 为数组契约，不分页；按 event_id 升序稳定输出） */
    List<AiT4AlertEvent> findAllByOrderByEventIdAsc();
}
