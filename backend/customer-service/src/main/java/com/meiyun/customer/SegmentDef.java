package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * AI 客户分群定义（M3-B3 / DESIGN-M3 §3 D2 M3-14），权威 DDL 见 V56__segment_def.sql。
 *
 * <p>conditions 为 JSONB 结构化条件数组 [{kind,...,label}]，kind 白名单见 SegmentService.CONDITION_KINDS；
 * RULE 引擎实时扫描命中回写 customer_count/share_pct 快照；AI 分群由画像 sync-profile 落库（D3-3）。
 */
@Entity
@Table(name = "segment_def")
public class SegmentDef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "segment_no", nullable = false, length = 16)
    private String segmentNo;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "ai_status", nullable = false, length = 8)
    private String aiStatus = "RULE";

    /** JSONB 原文（String 直存：datasource stringtype=unspecified 规避 jsonb/varchar 绑定问题）。 */
    @Column(nullable = false, columnDefinition = "jsonb")
    private String conditions = "[]";

    @Column(name = "rule_summary", nullable = false, length = 200)
    private String ruleSummary = "";

    @Column(name = "customer_count", nullable = false)
    private Integer customerCount = 0;

    @Column(name = "share_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal sharePct = BigDecimal.ZERO;

    @Column(name = "ai_suggestion", columnDefinition = "text")
    private String aiSuggestion;

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "client_token", length = 64)
    private String clientToken;

    @Column(name = "source_profile_id")
    private Long sourceProfileId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (aiStatus == null) aiStatus = "RULE";
        if (conditions == null || conditions.isBlank()) conditions = "[]";
        if (ruleSummary == null) ruleSummary = "";
        if (customerCount == null) customerCount = 0;
        if (sharePct == null) sharePct = BigDecimal.ZERO;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSegmentNo() { return segmentNo; }
    public void setSegmentNo(String segmentNo) { this.segmentNo = segmentNo; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getAiStatus() { return aiStatus; }
    public void setAiStatus(String aiStatus) { this.aiStatus = aiStatus; }
    public String getConditions() { return conditions; }
    public void setConditions(String conditions) { this.conditions = conditions; }
    public String getRuleSummary() { return ruleSummary; }
    public void setRuleSummary(String ruleSummary) { this.ruleSummary = ruleSummary; }
    public Integer getCustomerCount() { return customerCount; }
    public void setCustomerCount(Integer customerCount) { this.customerCount = customerCount; }
    public BigDecimal getSharePct() { return sharePct; }
    public void setSharePct(BigDecimal sharePct) { this.sharePct = sharePct; }
    public String getAiSuggestion() { return aiSuggestion; }
    public void setAiSuggestion(String aiSuggestion) { this.aiSuggestion = aiSuggestion; }
    public String getStoreCode() { return storeCode; }
    public void setStoreCode(String storeCode) { this.storeCode = storeCode; }
    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public Long getSourceProfileId() { return sourceProfileId; }
    public void setSourceProfileId(Long sourceProfileId) { this.sourceProfileId = sourceProfileId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
