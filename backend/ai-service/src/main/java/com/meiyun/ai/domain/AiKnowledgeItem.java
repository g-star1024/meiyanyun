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
@Table(name = "ai_knowledge_item")
@Getter
@Setter
@NoArgsConstructor
public class AiKnowledgeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "doc_id")
    private Long docId;

    @Column(name = "title", nullable = false)
    private String title;

    /** project / script / compliance */
    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "content", nullable = false)
    private String content;

    /** 顿号/逗号分隔的标签串 */
    @Column(name = "tags")
    private String tags;

    @Column(name = "source")
    private String source;

    /** PENDING / INDEXED / FAILED */
    @Column(name = "index_status", nullable = false)
    private String indexStatus = "INDEXED";

    @Column(name = "index_note")
    private String indexNote;

    @Column(name = "refs_count", nullable = false)
    private Long refsCount = 0L;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "store_code")
    private String storeCode;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
