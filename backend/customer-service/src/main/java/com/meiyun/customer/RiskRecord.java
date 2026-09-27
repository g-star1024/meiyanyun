package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 风控记录（M3-B6 / DESIGN-M3 §3 M3-17，表 risk_record / V59）。
 * 黑/风险名单＋拉黑解黑审批状态机：提交拉黑→PENDING_REVIEW；审核通过→BLACKLISTED；
 * 审核驳回→WATCHING；解除风险 BLACKLISTED|WATCHING→RELEASED（迁移前置校验在
 * RiskService，中文 4xx；时间线 JSONB 逐步追加，照前端 mock 活规格）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "risk_record")
public class RiskRecord {

    /** 风险级别三值。 */
    public static final String LEVEL_HIGH = "HIGH";
    public static final String LEVEL_MEDIUM = "MEDIUM";
    public static final String LEVEL_LOW = "LOW";

    /** 风控原因五值。 */
    public static final String REASON_FRAUD = "FRAUD";
    public static final String REASON_CHARGEBACK = "CHARGEBACK";
    public static final String REASON_MALICIOUS_COMPLAINT = "MALICIOUS_COMPLAINT";
    public static final String REASON_ILLEGAL_PRACTICE = "ILLEGAL_PRACTICE";
    public static final String REASON_OTHER = "OTHER";

    /** 状态机四态。 */
    public static final String STATUS_BLACKLISTED = "BLACKLISTED";
    public static final String STATUS_WATCHING = "WATCHING";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 风控单号：RK+yyyyMMdd-6位当日序号，DB 当日最大号+1。 */
    @Column(name = "risk_no", nullable = false, unique = true, length = 32)
    private String riskNo;

    /** 客户逻辑引用（无物理外键；演示种子为 C-3xx 字面值）。 */
    @Column(name = "customer_id", length = 32)
    private String customerId;

    @Column(name = "customer_name", nullable = false, length = 64)
    private String customerName;

    /** 手机号脱敏串（列表层即脱敏态直存）。 */
    @Column(name = "phone_mask", nullable = false, length = 16)
    private String phoneMask;

    @Column(name = "level", nullable = false, length = 8)
    private String level;

    @Column(name = "reason", nullable = false, length = 32)
    private String reason;

    @Column(name = "reason_detail", nullable = false, columnDefinition = "text")
    private String reasonDetail;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "hit_count", nullable = false)
    private Integer hitCount;

    @Column(name = "block_transactions", nullable = false)
    private Boolean blockTransactions;

    /** 提交操作人（DataScope.currentActor()；系统自动命中时为「系统自动」）。 */
    @Column(name = "operator", nullable = false, length = 64)
    private String operator;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    /** 时间线 JSONB：[{action,by,at,comment?}]。 */
    @Column(name = "timeline", nullable = false, columnDefinition = "jsonb")
    private String timeline;

    /** 门店编码：NULL=全连锁（铁律-1-D）。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (status == null) status = STATUS_PENDING_REVIEW;
        if (hitCount == null) hitCount = 1;
        if (blockTransactions == null) blockTransactions = Boolean.FALSE;
        if (phoneMask == null) phoneMask = "";
        if (reasonDetail == null) reasonDetail = "";
        if (operator == null) operator = "";
        if (timeline == null) timeline = "[]";
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
