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
@Table(name = "ai_scheduling_plan")
@Getter
@Setter
@NoArgsConstructor
public class AiSchedulingPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "plan_id")
    private Long planId;

    /** 方案周周一日期 yyyy-MM-dd（Asia/Shanghai） */
    @Column(name = "week_start", nullable = false)
    private String weekStart;

    @Column(name = "store_code", nullable = false)
    private String storeCode = "";

    /** DRAFT / ADOPTED */
    @Column(name = "status", nullable = false)
    private String status = "DRAFT";

    @Column(name = "forecast_total", nullable = false)
    private Integer forecastTotal = 0;

    /** 建议排班人次合计（人·班） */
    @Column(name = "slot_total", nullable = false)
    private Integer slotTotal = 0;

    @Column(name = "staff_pool_count", nullable = false)
    private Integer staffPoolCount = 0;

    @Column(name = "gap_slots", nullable = false)
    private Integer gapSlots = 0;

    /** 规则估算人力成本，单位：分（岗位参考班薪，非真实工资） */
    @Column(name = "cost_fen", nullable = false)
    private Long costFen = 0L;

    @Column(name = "forecast_json", nullable = false)
    private String forecastJson = "[]";

    @Column(name = "notes_json", nullable = false)
    private String notesJson = "[]";

    @Column(name = "summary", nullable = false)
    private String summary = "";

    @Column(name = "raw_output", nullable = false)
    private String rawOutput = "";

    @Column(name = "invoke_log_id")
    private Long invokeLogId;

    @Column(name = "model_code")
    private String modelCode;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    /** LLM 调用费用，单位：分（与规则估算 costFen 不同口径） */
    @Column(name = "llm_cost_fen", nullable = false)
    private Long llmCostFen = 0L;

    @Column(name = "adopted_at")
    private OffsetDateTime adoptedAt;

    @Column(name = "adopted_by")
    private String adoptedBy;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
