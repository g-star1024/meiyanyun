package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiT4FeatureLineageRepository extends JpaRepository<AiT4FeatureLineage, Long> {

    /** 节点/边按入库序全量直返（图规模小，前端 lineage 为 nodes+edges 数组契约） */
    List<AiT4FeatureLineage> findByKindOrderByLineageIdAsc(String kind);

    /** SOURCE 节点幂等预检（注册同步血缘时 src-{source} 不存在才插） */
    boolean existsByKindAndNodeId(String kind, String nodeId);

    /** 边幂等预检（避免部分唯一索引冲突 500） */
    boolean existsByKindAndFromNodeAndToNode(String kind, String fromNode, String toNode);
}
