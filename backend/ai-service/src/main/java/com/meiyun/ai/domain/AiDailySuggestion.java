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
@Table(name = "ai_daily_suggestion")
@Getter
@Setter
@NoArgsConstructor
public class AiDailySuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "suggestion_id")
    private Long suggestionId;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    /** 类型 code：core 核心 / anomaly 异常 / action 行动 */
    @Column(name = "suggestion_type", nullable = false)
    private String suggestionType = "action";

    @Column(name = "title", nullable = false)
    private String title = "";

    @Column(name = "detail", nullable = false)
    private String detail = "";

    /** 是否已采纳为任务（站内登记；真实任务下发为远期 Backlog） */
    @Column(name = "adopted", nullable = false)
    private Boolean adopted = false;

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
