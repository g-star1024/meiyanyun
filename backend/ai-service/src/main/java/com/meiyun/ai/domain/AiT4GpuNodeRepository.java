package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiT4GpuNodeRepository extends JpaRepository<AiT4GpuNode, Long> {

    Optional<AiT4GpuNode> findByCode(String code);

    boolean existsByCode(String code);

    /** GPU 节点列表全量直返（节点量级小，前端 gpus 为数组契约，不分页） */
    List<AiT4GpuNode> findAllByOrderByNodeIdAsc();
}
