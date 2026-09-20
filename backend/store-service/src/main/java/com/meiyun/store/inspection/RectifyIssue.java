package com.meiyun.store.inspection;

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
@Table(name = "rectify_issue",
        indexes = @Index(name = "idx_rect_issue_ins", columnList = "ins_id"))
@Getter
@Setter
@NoArgsConstructor
public class RectifyIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ins_id", nullable = false)
    private Long insId;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "owner", nullable = false, length = 64)
    private String owner = "待分配";

    @Column(name = "status", nullable = false, length = 16)
    private String status = "OPEN";

    @Column(name = "due_at", nullable = false)
    private OffsetDateTime dueAt;

    @Column(name = "has_photo", nullable = false)
    private Boolean hasPhoto = false;
}
