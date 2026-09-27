package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 数据血缘-边（T2-B1 / DESIGN-T2，表 data_lineage_edge / V66）。
 * from_node/to_node 逻辑引用 data_lineage_node.id（零物理外键）；
 * 列名避让 SQL 保留字 from/to，API 序列化映射回 from/to。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "data_lineage_edge")
public class DataLineageEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 源节点 id（契约字段 from，保留字避让）。 */
    @Column(name = "from_node", nullable = false, length = 32)
    private String fromNode;

    /** 目标节点 id（契约字段 to，保留字避让）。 */
    @Column(name = "to_node", nullable = false, length = 32)
    private String toNode;
}
