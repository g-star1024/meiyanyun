package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiT4ModelVersionRepository extends JpaRepository<AiT4ModelVersion, Long> {

    List<AiT4ModelVersion> findByModelIdOrderByVersionIdAsc(Long modelId);

    /** 列表嵌套：按主表 id 集一次拉回，Service 内存分组，避免 N+1 */
    List<AiT4ModelVersion> findByModelIdInOrderByVersionIdAsc(Collection<Long> modelIds);

    Optional<AiT4ModelVersion> findByModelIdAndVersion(Long modelId, String version);

    boolean existsByModelIdAndVersion(Long modelId, String version);

    /** 发布联动：找同模型其余 PUBLISHED 版本回退 READY */
    List<AiT4ModelVersion> findByModelIdAndStatus(Long modelId, String status);
}
