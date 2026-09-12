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
@Table(name = "ai_approval")
@Getter
@Setter
@NoArgsConstructor
public class AiApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "approval_id")
    private Long approvalId;

    /** PROVIDER / MODEL / BINDING */
    @Column(name = "approval_type", nullable = false)
    private String approvalType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "applicant", nullable = false)
    private String applicant;

    @Column(name = "applied_at", insertable = false, updatable = false)
    private OffsetDateTime appliedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    /** PENDING / APPROVED / REJECTED */
    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    @Column(name = "opinion")
    private String opinion;
}
