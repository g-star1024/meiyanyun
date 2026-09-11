package com.meiyun.txn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 术后随访 SOP 编排模板（followup_sop_template，P5-B30）。
 *
 * <p>模板与节点（{@link FollowupSopTemplateNode}）一对多。store_code 为 NULL 表示集团通用模板
 * （全部门店可见可用）；有值为门店自建模板。停用 enabled=false 后不再用于自动排程，
 * 但已生成的随访节点不受影响（节点只冗余 stage/label，不留外键）。
 * 当前版本仅播种一个集团通用模板（四节点），完成治疗排程时读取全部启用节点。</p>
 */
@Entity
@Table(name = "followup_sop_template", indexes = {
        @Index(name = "idx_sop_tpl_store", columnList = "store_code,enabled")
})
@Getter @Setter @NoArgsConstructor
public class FollowupSopTemplate {

    /** 模板号：SPT-SEED-001（种子）等，字符串主键。 */
    @Id
    @Column(name = "template_no", length = 24)
    private String templateNo;

    @Column(nullable = false, length = 64)
    private String name;

    /** 门店码：NULL=集团通用；有值=门店自建。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (enabled == null) enabled = true;
    }
}
