package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Flow 执行日志（P5-B90）：idem_key={ruleNo}:{customerId}:{triggerDate} 唯一约束幂等防重核心。
 * status 三态：SUCCESS 成功 / SKIPPED 跳过 / FAILED 失败（下轮自愈）。
 */
@Entity
@Table(name = "automation_log")
@Getter @Setter @NoArgsConstructor
public class AutomationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 逻辑引用 automation_rule.rule_no（不建物理外键）。 */
    @Column(name = "rule_no", nullable = false, length = 32)
    private String ruleNo;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    /** 触发业务日（业务时区 +8）。 */
    @Column(name = "trigger_date", nullable = false)
    private LocalDate triggerDate;

    /** CREATE_CARE_TASK / CREATE_RECALL。 */
    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;

    /** 动作产物单号：生成的 care_no / recall_no。 */
    @Column(name = "action_ref", length = 64)
    private String actionRef;

    /** SUCCESS / SKIPPED / FAILED。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 跳过 / 失败原因（中文）。 */
    @Column(length = 500)
    private String message;

    /** 幂等键：{ruleNo}:{customerId}:{triggerDate}，查重即跳。 */
    @Column(name = "idem_key", nullable = false, unique = true, length = 128)
    private String idemKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
