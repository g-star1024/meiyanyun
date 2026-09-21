package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customer_merge_snapshot", indexes = {
        @Index(name = "idx_customer_merge_snapshot_merge", columnList = "merge_id")
})
@Getter @Setter @NoArgsConstructor
public class CustomerMergeSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merge_id", nullable = false, length = 20)
    private String mergeId;                   // 关联 customer_merge

    @Column(name = "table_name", nullable = false, length = 32)
    private String tableName;                 // 被迁移子表名；table_name='customer' 行为 loser 全字段快照

    @Column(name = "row_ids", columnDefinition = "jsonb", nullable = false)
    private String rowIds;                    // 本次从 merged_id 迁出的主键/行标识数组（JSON）

    @Column(name = "moved_count", nullable = false)
    private Integer movedCount;

    @Column(name = "loser_before", columnDefinition = "jsonb")
    private String loserBefore;               // 仅 table_name='customer' 行有值：loser 合并前全字段快照

    public CustomerMergeSnapshot(String mergeId, String tableName, String rowIds, Integer movedCount) {
        this.mergeId = mergeId;
        this.tableName = tableName;
        this.rowIds = rowIds;
        this.movedCount = movedCount;
    }
}
