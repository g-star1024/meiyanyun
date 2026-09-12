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
@Table(name = "ai_sensitive_hit")
@Getter
@Setter
@NoArgsConstructor
public class AiSensitiveHit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "hit_id")
    private Long hitId;

    @Column(name = "hit_at", insertable = false, updatable = false)
    private OffsetDateTime hitAt;

    /** 命中词库 id（词被删后保留 word/category 快照，故可空） */
    @Column(name = "word_id")
    private Long wordId;

    @Column(name = "word", nullable = false)
    private String word;

    /** BANNED / INJECTION */
    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "feature_code")
    private String featureCode;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "store_code")
    private String storeCode;

    @Column(name = "context_snippet")
    private String contextSnippet;

    @Column(name = "false_positive", nullable = false)
    private Boolean falsePositive = false;

    @Column(name = "marked_by")
    private String markedBy;

    @Column(name = "marked_at")
    private OffsetDateTime markedAt;
}
