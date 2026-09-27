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
 * 客诉单（M3-B8 / DESIGN-M3 §3 M3-20，表 complaint / V61）。
 * 状态机五态照前端 mock 活规格：PENDING_ACCEPT→[PROCESSING,REJECTED]；
 * PROCESSING→[PENDING_REVIEW,REJECTED]；PENDING_REVIEW→[CLOSED,PROCESSING,REJECTED]；
 * CLOSED/REJECTED 终态（迁移前置校验在 ComplaintService，中文 4xx）。
 * 赔付金额存分（compensation_amount_cents），签署层级服务端统算（>=20000 元→L3 / >=5000 元→L2 / 否则 L1）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "complaint")
public class Complaint {

    /** 投诉来源四值。 */
    public static final String SOURCE_STORE = "STORE";
    public static final String SOURCE_PHONE = "PHONE";
    public static final String SOURCE_ONLINE = "ONLINE";
    public static final String SOURCE_THIRD_PARTY = "THIRD_PARTY";

    /** 严重度三值。 */
    public static final String SEVERITY_LOW = "LOW";
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    public static final String SEVERITY_HIGH = "HIGH";

    /** 投诉分类五值。 */
    public static final String CATEGORY_SERVICE = "SERVICE";
    public static final String CATEGORY_MEDICAL = "MEDICAL";
    public static final String CATEGORY_BILLING = "BILLING";
    public static final String CATEGORY_OUTCOME = "OUTCOME";
    public static final String CATEGORY_OTHER = "OTHER";

    /** 状态机五态。 */
    public static final String STATUS_PENDING_ACCEPT = "PENDING_ACCEPT";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_REJECTED = "REJECTED";

    /** 签署层级三值。 */
    public static final String TIER_L1 = "L1";
    public static final String TIER_L2 = "L2";
    public static final String TIER_L3 = "L3";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 客诉单号：TS+yyyyMMdd-3位当日序号，DB 当日最大号+1。 */
    @Column(name = "complaint_no", nullable = false, unique = true, length = 32)
    private String complaintNo;

    /** 客户逻辑引用（无物理外键；演示种子为 C-4xx 字面值）。 */
    @Column(name = "customer_id", length = 32)
    private String customerId;

    @Column(name = "customer_name", nullable = false, length = 64)
    private String customerName;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "severity", nullable = false, length = 8)
    private String severity;

    @Column(name = "category", nullable = false, length = 16)
    private String category;

    @Column(name = "medical_risk", nullable = false)
    private Boolean medicalRisk;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "related_order_no", length = 32)
    private String relatedOrderNo;

    /** 门店编码：登记人门店（NULL=全连锁身份登记，铁律-1-D）。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "store_name", length = 64)
    private String storeName;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 赔付金额（分）：DB 存分，API 层元↔分换算。 */
    @Column(name = "compensation_amount_cents", nullable = false)
    private Long compensationAmountCents;

    @Column(name = "sign_tier", nullable = false, length = 4)
    private String signTier;

    @Column(name = "resolution", columnDefinition = "text")
    private String resolution;

    @Column(name = "accepted_by_name", length = 64)
    private String acceptedByName;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "submitted_by_name", length = 64)
    private String submittedByName;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "closed_by_name", length = 64)
    private String closedByName;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (status == null) status = STATUS_PENDING_ACCEPT;
        if (medicalRisk == null) medicalRisk = Boolean.FALSE;
        if (compensationAmountCents == null) compensationAmountCents = 0L;
        if (signTier == null) signTier = TIER_L1;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
