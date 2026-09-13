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
@Table(name = "ai_customer_profile")
@Getter
@Setter
@NoArgsConstructor
public class AiCustomerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long profileId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "store_code")
    private String storeCode;

    /** 客户价值分 0~100 */
    @Column(name = "value_score", nullable = false)
    private Integer valueScore = 0;

    /** 所属分群名数组 JSON */
    @Column(name = "groups_json", nullable = false)
    private String groupsJson = "[]";

    /** 画像标签数组 JSON：[{label,status}] */
    @Column(name = "tags_json", nullable = false)
    private String tagsJson = "[]";

    /** 模型画像输出原文（截断 8000 字） */
    @Column(name = "raw_output", nullable = false)
    private String rawOutput = "";

    @Column(name = "invoke_log_id")
    private Long invokeLogId;

    @Column(name = "model_code")
    private String modelCode;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    /** 费用，单位：分 */
    @Column(name = "cost_fen", nullable = false)
    private Long costFen = 0L;

    /** 是否已登记应用到分群（M3-06/M3-14 跨域推送为远期 Backlog） */
    @Column(name = "applied_to_segment", nullable = false)
    private Boolean appliedToSegment = false;

    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

    @Column(name = "applied_by")
    private String appliedBy;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
