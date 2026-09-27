package com.meiyun.org.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * T3 数据中台 T+1 对账批次（DESIGN-T3 §三，表 integration_reconcile_batch / V73）。
 * uk(connector_id, biz_date) 幂等锚：重放返既有批次；connector_id=0=全部连接器汇总。
 * 本地口径：matched=remote_ack 置 MATCHED；long/short/diff 恒 0 如实（三方账单文件留 §7）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "integration_reconcile_batch")
public class IntegrationReconcileBatch {

    /** connector_id=0 语义：全部连接器汇总批次。 */
    public static final long SCOPE_ALL = 0L;

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_no", nullable = false, length = 40)
    private String batchNo;

    @Column(name = "connector_id", nullable = false)
    private Long connectorId;

    @Column(name = "biz_date", nullable = false)
    private LocalDate bizDate;

    @Column(name = "total_count", nullable = false)
    private Integer totalCount;

    @Column(name = "matched_count", nullable = false)
    private Integer matchedCount;

    @Column(name = "pending_count", nullable = false)
    private Integer pendingCount;

    @Column(name = "long_count", nullable = false)
    private Integer longCount;

    @Column(name = "short_count", nullable = false)
    private Integer shortCount;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "diff_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal diffAmount;

    @Column(name = "status", nullable = false, length = 8)
    private String status;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
}
