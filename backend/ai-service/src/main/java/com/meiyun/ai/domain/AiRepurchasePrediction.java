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
@Table(name = "ai_repurchase_prediction")
@Getter
@Setter
@NoArgsConstructor
public class AiRepurchasePrediction {

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

    /** 预测周期：week/month/quarter */
    @Column(name = "period", nullable = false)
    private String period;

    /** 预测窗口天数：7/30/90 */
    @Column(name = "horizon_days", nullable = false)
    private Integer horizonDays = 7;

    /** 项目品类归类 code：skin/inject/anti/body/other */
    @Column(name = "project_code", nullable = false)
    private String projectCode = "other";

    /** 最近一次真实已收款订单项目中文名 */
    @Column(name = "project_name", nullable = false)
    private String projectName = "";

    /** 复购概率 0~100 */
    @Column(name = "prob", nullable = false)
    private Integer prob = 0;

    /** 推荐时机文案 */
    @Column(name = "timing", nullable = false)
    private String timing = "";

    /** 预计转化金额（分） */
    @Column(name = "expected_amount", nullable = false)
    private Long expectedAmount = 0L;

    /** 入模真实信号 JSON */
    @Column(name = "signals_json", nullable = false)
    private String signalsJson = "{}";

    /** 模型预测输出原文（截断 8000 字） */
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

    /** 是否已登记建跟进（M3-08 下发为远期 Backlog） */
    @Column(name = "followup_registered", nullable = false)
    private Boolean followupRegistered = false;

    @Column(name = "followup_at")
    private OffsetDateTime followupAt;

    @Column(name = "followup_by")
    private String followupBy;

    /** 是否已登记推送（M5-03 下发为远期 Backlog） */
    @Column(name = "push_registered", nullable = false)
    private Boolean pushRegistered = false;

    @Column(name = "push_at")
    private OffsetDateTime pushAt;

    @Column(name = "push_by")
    private String pushBy;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
