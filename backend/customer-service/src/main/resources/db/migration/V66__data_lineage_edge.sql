CREATE TABLE IF NOT EXISTS data_lineage_edge (
    id BIGSERIAL PRIMARY KEY,
    from_node VARCHAR(32) NOT NULL,
    to_node VARCHAR(32) NOT NULL
);

COMMENT ON TABLE data_lineage_edge IS 'T2-B1 数据血缘-边表（from_node/to_node 逻辑引用 data_lineage_node.id，零物理外键）';
COMMENT ON COLUMN data_lineage_edge.id IS '主键';
COMMENT ON COLUMN data_lineage_edge.from_node IS '源节点 id（注：前端契约字段名为 from，from 为 SQL 保留字故列名避让，API 序列化映射回 from）';
COMMENT ON COLUMN data_lineage_edge.to_node IS '目标节点 id（注：前端契约字段名为 to，to 为 SQL 保留字故列名避让，API 序列化映射回 to）';

CREATE INDEX IF NOT EXISTS idx_lineage_edge_from ON data_lineage_edge(from_node);
