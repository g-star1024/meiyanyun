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
@Table(name = "ai_churn_prediction")
@Getter
@Setter
@NoArgsConstructor
public class AiChurnPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "prediction_id")
    private Long predictionId;

    @Column(name = "batch_no", nullable = false)
    private String batchNo;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "store_code")
    private String storeCode;

    /** 风险分级 code：high/mid/low（由 score 阈值映射） */
    @Column(name = "risk_level", nullable = false)
    private String riskLevel = "low";

    /** 流失风险分 0~100，越高越危险 */
    @Column(name = "score", nullable = false)
    private Integer score = 0;

    /** 关键因子文案（模型从入模真实信号择一概括） */
    @Column(name = "key_factor", nullable = false)
    private String keyFactor = "";

    /** 建议干预文案（运营建议，非已执行动作） */
    @Column(name = "suggested_action", nullable = false)
    private String suggestedAction = "";

    /** 最近一次真实成交日期 yyyy-MM-dd */
    @Column(name = "last_visit_date", nullable = false)
    private String lastVisitDate = "";

    /** 距上次成交天数 */
    @Column(name = "recency_days")
    private Long recencyDays;

    /** 近 90 天对比再前 90 天消费下降百分比 0~100；前期为 0 → null */
    @Column(name = "spend_decline_pct")
    private Integer spendDeclinePct;

    /** 入模真实信号 JSON */
    @Column(name = "signals_json", nullable = false)
    private String signalsJson = "{}";

    /** 模型评分输出原文（截断 8000 字） */
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

    /** 是否已登记干预（M3-10/M2-17/M5-03 真实下发为远期 Backlog） */
    @Column(name = "intervene_registered", nullable = false)
    private Boolean interveneRegistered = false;

    @Column(name = "intervene_at")
    private OffsetDateTime interveneAt;

    @Column(name = "intervene_by")
    private String interveneBy;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
