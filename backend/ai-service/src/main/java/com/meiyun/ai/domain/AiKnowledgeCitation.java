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
@Table(name = "ai_knowledge_citation")
@Getter
@Setter
@NoArgsConstructor
public class AiKnowledgeCitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "citation_id")
    private Long citationId;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "query", nullable = false)
    private String query;

    /** manual_search / scripts / chatbot / content */
    @Column(name = "source_feature", nullable = false)
    private String sourceFeature = "manual_search";

    /** null 未反馈 / true 有用 / false 无用 */
    @Column(name = "useful")
    private Boolean useful;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "store_code")
    private String storeCode;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
