package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 分诊改派历史（append-only）：每次改派同事务追加一行，triage 当前态仍由 {@link Triage}
 * 一对一行承载（forwarded_to 为当前负责人）。历史按 id 正序即改派发生次序。
 */
@Entity
@Table(name = "triage_reassign")
@Getter @Setter @NoArgsConstructor
public class TriageReassign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属分诊单号（triage.tr_no）。 */
    @Column(name = "tr_no", nullable = false, length = 24)
    private String trNo;

    /** 关联到店登记号（ah_no），列表批量拉取用。 */
    @Column(name = "arrival_id", nullable = false, length = 24)
    private String arrivalId;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 改派前负责人工号（首诊后为 assigned_to，再次改派为上一次 forwarded_to）。 */
    @Column(name = "from_staff", nullable = false, length = 32)
    private String fromStaff;

    /** 改派后负责人工号。 */
    @Column(name = "to_staff", nullable = false, length = 32)
    private String toStaff;

    /** 执行改派的登录人工号（JWT 当前操作者）。 */
    @Column(nullable = false, length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
