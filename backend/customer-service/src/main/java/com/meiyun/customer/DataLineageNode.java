package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 数据血缘-节点（T2-B1 / DESIGN-T2，表 data_lineage_node / V65）。
 * id 为字符串业务标识（如 src-mysql/tab-orders，锚定前端 mock 逐字 id），非自增。
 * x/y 为前端血缘画布坐标，服务端透传。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "data_lineage_node")
public class DataLineageNode {

    /** 节点类型五值。 */
    public static final String TYPE_SOURCE = "SOURCE";
    public static final String TYPE_TABLE = "TABLE";
    public static final String TYPE_TAG = "TAG";
    public static final String TYPE_API = "API";
    public static final String TYPE_REPORT = "REPORT";

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "node_type", nullable = false, length = 8)
    private String nodeType;

    @Column(name = "x", nullable = false)
    private Integer x;

    @Column(name = "y", nullable = false)
    private Integer y;
}
