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
@Table(name = "ai_daily_report")
@Getter
@Setter
@NoArgsConstructor
public class AiDailyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long reportId;

    /** 业务自然日 yyyy-MM-dd（Asia/Shanghai 日界） */
    @Column(name = "report_date", nullable = false)
    private String reportDate;

    @Column(name = "store_code", nullable = false)
    private String storeCode = "";

    @Column(name = "revenue_fen", nullable = false)
    private Long revenueFen = 0L;

    @Column(name = "paid_order_count", nullable = false)
    private Long paidOrderCount = 0L;

    @Column(name = "arrival_count", nullable = false)
    private Long arrivalCount = 0L;

    @Column(name = "new_customer_count", nullable = false)
    private Long newCustomerCount = 0L;

    @Column(name = "refund_count", nullable = false)
    private Long refundCount = 0L;

    @Column(name = "refund_fen", nullable = false)
    private Long refundFen = 0L;

    @Column(name = "contra_yellow_count", nullable = false)
    private Long contraYellowCount = 0L;

    @Column(name = "contra_red_count", nullable = false)
    private Long contraRedCount = 0L;

    @Column(name = "anomaly_count", nullable = false)
    private Long anomalyCount = 0L;

    /** 交易域指标原文 JSON（含环比上一自然日） */
    @Column(name = "metrics_json", nullable = false)
    private String metricsJson = "{}";

    /** LLM 经营摘要（容错解析，失败为兜底文案） */
    @Column(name = "summary", nullable = false)
    private String summary = "";

    /** 模型输出原文（截断 8000 字） */
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

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
