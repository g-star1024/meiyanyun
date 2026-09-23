package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 营销日历节点（P5-B88）。
 * 三类：member 会员日 / festival 节日 / campaign 活动（英文码落库，前端经 label 映射中文）。
 * 全连锁资产，无门店维度（D2-A）。
 */
@Entity
@Table(name = "calendar_node")
@Getter @Setter @NoArgsConstructor
public class CalendarNode {

    @Id
    @Column(name = "node_id", length = 24)
    private String nodeId;

    @Column(name = "node_date", nullable = false)
    private LocalDate nodeDate;

    @Column(nullable = false, length = 64)
    private String title;

    /** member / festival / campaign。 */
    @Column(name = "node_type", nullable = false, length = 16)
    private String nodeType;

    @Column(name = "node_desc", length = 256)
    private String nodeDesc;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
