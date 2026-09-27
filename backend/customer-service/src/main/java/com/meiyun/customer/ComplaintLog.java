package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 客诉时间线（M3-B8 / DESIGN-M3 §3 M3-20，表 complaint_log / V62）。
 * complaint 的逐步操作留痕：登记投诉/受理投诉/提交处理方案/审批结案/退回补充处理/驳回投诉。
 * 列名注记：前端契约 `by` 系 SQL 保留字 → by_name；`at` → at_time（API 序列化映射回原字段名）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "complaint_log")
public class ComplaintLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 客诉单逻辑引用（complaint.id，无物理外键）。 */
    @Column(name = "complaint_id", nullable = false)
    private Long complaintId;

    /** 操作时刻（前端契约字段 at）。 */
    @Column(name = "at_time", nullable = false)
    private OffsetDateTime atTime;

    /** 操作人（前端契约字段 by）。 */
    @Column(name = "by_name", nullable = false, length = 64)
    private String byName;

    /** 动作中文名。 */
    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @PrePersist
    void prePersist() {
        if (atTime == null) atTime = OffsetDateTime.now();
    }
}
