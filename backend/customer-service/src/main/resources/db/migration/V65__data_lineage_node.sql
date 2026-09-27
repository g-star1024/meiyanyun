CREATE TABLE IF NOT EXISTS data_lineage_node (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    node_type VARCHAR(8) NOT NULL,
    x INT NOT NULL DEFAULT 0,
    y INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_lineage_node_type CHECK (node_type IN ('SOURCE','TABLE','TAG','API','REPORT'))
);

COMMENT ON TABLE data_lineage_node IS 'T2-B1 数据血缘-节点表（id 为字符串业务标识如 src-mysql/tab-orders，非自增）';
COMMENT ON COLUMN data_lineage_node.id IS '节点标识（字符串主键，锚定前端 mock 逐字 id）';
COMMENT ON COLUMN data_lineage_node.name IS '节点名';
COMMENT ON COLUMN data_lineage_node.node_type IS '节点类型：SOURCE/TABLE/TAG/API/REPORT';
COMMENT ON COLUMN data_lineage_node.x IS '画布 x 坐标';
COMMENT ON COLUMN data_lineage_node.y IS '画布 y 坐标';
