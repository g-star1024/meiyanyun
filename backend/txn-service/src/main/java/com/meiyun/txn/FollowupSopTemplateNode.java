package com.meiyun.txn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 术后 SOP 模板节点（followup_sop_template_node，P5-B30）。
 *
 * <p>一个节点 = 术后第 day_offset 天 + 回访方式 method + 阶段 stage + 名称 label。
 * 对齐前端 SopNodeDef：内置四阶段 CARE_24H/FOLLOWUP_3D/RECOVERY_7D/REVISIT_30D（偏移 1/3/7/30），
 * 自定义节点 stage=MANUAL。停用 enabled=false 后不参与自动排程。line_no 决定节点顺序。</p>
 */
@Entity
@Table(name = "followup_sop_template_node", indexes = {
        @Index(name = "idx_sop_node_tpl", columnList = "template_no,line_no")
})
@Getter @Setter @NoArgsConstructor
public class FollowupSopTemplateNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_no", nullable = false, length = 24)
    private String templateNo;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    /** 阶段：CARE_24H/FOLLOWUP_3D/RECOVERY_7D/REVISIT_30D/MANUAL。 */
    @Column(nullable = false, length = 16)
    private String stage;

    @Column(nullable = false, length = 64)
    private String label;

    /** 术后第 N 天（SOP 第 0 天=治疗日）。 */
    @Column(name = "day_offset", nullable = false)
    private int dayOffset;

    /** 回访方式：PHONE/WECHAT/IN_STORE。 */
    @Column(nullable = false, length = 16)
    private String method;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (enabled == null) enabled = true;
    }
}
