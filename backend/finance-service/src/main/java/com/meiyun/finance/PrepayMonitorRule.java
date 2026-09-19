package com.meiyun.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 预收合规监控规则（B63 卡3 L85，V38 prepay_monitor_rule）。
 *
 * <p>三类规则：DEPOSIT_AGE 充值沉淀账龄（unit=DAY）/ REFUND_PENDING 退款待核销金额（FEN）
 * / DORMANT_CARD 沉睡卡沉淀（FEN）。行级 enabled 默认 false，store_code 非空时为门店白名单
 * 灰度（NULL=全店）——首启零行＋默认关＋白名单三层防雪崩。
 */
@Entity
@Table(name = "prepay_monitor_rule")
@Getter
@Setter
@NoArgsConstructor
public class PrepayMonitorRule {

    @Id
    @Column(name = "code", length = 16)
    private String code;

    @Column(name = "rule_name", length = 64, nullable = false)
    private String ruleName;

    /** DEPOSIT_AGE / REFUND_PENDING / DORMANT_CARD。 */
    @Column(name = "type", length = 16, nullable = false)
    private String type;

    /** NULL=全店；非空仅该门店生效（灰度白名单）。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "threshold_value", nullable = false)
    private Long thresholdValue;

    /** DAY / FEN。 */
    @Column(name = "threshold_unit", length = 8, nullable = false)
    private String thresholdUnit;

    /** HIGH / MEDIUM / LOW（HIGH→CRITICAL 站内信，其余 WARN）。 */
    @Column(name = "level", length = 8, nullable = false)
    private String level;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "remark", length = 256)
    private String remark;

    @Column(name = "created_by", length = 16, nullable = false)
    private String createdBy = "system";

    @Column(name = "updated_by", length = 16, nullable = false)
    private String updatedBy = "system";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (updatedAt == null) updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
