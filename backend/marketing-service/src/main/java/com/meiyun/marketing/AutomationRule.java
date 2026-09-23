package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 营销自动化 Flow 规则（P5-B90）：内建轻量 Trigger-Condition-Action 引擎（B69 定案）。
 * trigger_type：BIRTHDAY 生日 / DORMANT_DAYS 沉睡 N 天 / VISIT_GAP_DAYS 末次到店满 N 天。
 * action_type：CREATE_CARE_TASK 生成关怀任务 / CREATE_RECALL 生成复诊召回。
 * trigger_config / condition_config / action_config 为 JSONB 文本（String 承载，沿 AuditLog payload 惯例）。
 * store_code NULL=全部门店，填值=按客户归属门店过滤（铁律-1-D）。
 */
@Entity
@Table(name = "automation_rule")
@Getter @Setter @NoArgsConstructor
public class AutomationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 规则号：AR + yyyyMMdd + - + 6 位当日序号（BizNoGenerator），种子固定号 AR-SEED-xxx。 */
    @Column(name = "rule_no", nullable = false, unique = true, length = 32)
    private String ruleNo;

    /** 规则名（中文）。 */
    @Column(nullable = false, length = 64)
    private String name;

    /** BIRTHDAY / DORMANT_DAYS / VISIT_GAP_DAYS。 */
    @Column(name = "trigger_type", nullable = false, length = 32)
    private String triggerType;

    /** 触发配置 JSONB：BIRTHDAY={daysBefore}，DORMANT_DAYS={dormantDays}，VISIT_GAP_DAYS={gapDays}。 */
    @Column(name = "trigger_config", columnDefinition = "jsonb")
    private String triggerConfig;

    /** 条件配置 JSONB：{levels:[],tags:[]} 客户等级/标签预留，v1 引擎做空值安全基本匹配。 */
    @Column(name = "condition_config", columnDefinition = "jsonb")
    private String conditionConfig;

    /** CREATE_CARE_TASK / CREATE_RECALL。 */
    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;

    /** 动作配置 JSONB：CREATE_CARE_TASK={careType,channel,contentTemplate}，CREATE_RECALL={method,reason}。 */
    @Column(name = "action_config", columnDefinition = "jsonb")
    private String actionConfig;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /** 门店码：NULL=全部门店，填值=按客户归属门店过滤（铁律-1-D）。 */
    @Column(name = "store_code", length = 32)
    private String storeCode;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
