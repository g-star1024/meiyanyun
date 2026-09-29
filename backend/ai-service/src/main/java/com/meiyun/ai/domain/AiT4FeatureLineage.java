package com.meiyun.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_t4_feature_lineage")
@Getter
@Setter
@NoArgsConstructor
public class AiT4FeatureLineage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lineage_id")
    private Long lineageId;

    /** NODE=节点 / EDGE=边 */
    @Column(name = "kind", nullable = false)
    private String kind;

    /** 节点 id（NODE 行；FEATURE 节点=ai_t4_feature.code） */
    @Column(name = "node_id")
    private String nodeId;

    /** 节点显示名（NODE 行） */
    @Column(name = "node_name")
    private String nodeName;

    /** 节点类型（NODE 行）：SOURCE/FEATURE/MODEL/SERVICE */
    @Column(name = "node_type")
    private String nodeType;

    /** 上游节点 id（EDGE 行） */
    @Column(name = "from_node")
    private String fromNode;

    /** 下游节点 id（EDGE 行） */
    @Column(name = "to_node")
    private String toNode;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
