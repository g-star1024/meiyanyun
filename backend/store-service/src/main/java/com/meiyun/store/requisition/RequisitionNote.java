package com.meiyun.store.requisition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "requisition_note",
        indexes = @Index(name = "idx_rq_note_rq", columnList = "rq_id"))
@Getter
@Setter
@NoArgsConstructor
public class RequisitionNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rq_id", nullable = false)
    private Long rqId;

    @Column(name = "note_by", nullable = false, length = 64)
    private String noteBy;

    @Column(name = "content", nullable = false, length = 255)
    private String content;

    @Column(name = "note_at", nullable = false)
    private OffsetDateTime noteAt;
}
