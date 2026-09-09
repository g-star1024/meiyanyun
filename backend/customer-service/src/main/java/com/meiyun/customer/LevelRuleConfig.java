package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 会员升降级规则（B 端配置，单行 rule_id=1；对齐前端 LevelRule 四字段）。 */
@Entity
@Table(name = "level_rule_config")
@Getter @Setter @NoArgsConstructor
public class LevelRuleConfig {

    @Id
    @Column(name = "rule_id")
    private Integer ruleId;

    /** 等级计算周期文案（如「自然月（每月1号）」）。 */
    @Column(name = "calc_period", length = 32)
    private String calcPeriod;

    /** 降级保护期（月）。 */
    @Column(name = "downgrade_protect_months")
    private Integer downgradeProtectMonths;

    /** 达标后是否自动升级（本期自动升级为手动触发，定时批处理列 Backlog）。 */
    @Column(name = "auto_upgrade")
    private Boolean autoUpgrade;

    /** 消费积分倍率（全局规则字段，事件流接入后生效）。 */
    @Column(name = "points_multiplier", precision = 4, scale = 2)
    private BigDecimal pointsMultiplier;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
