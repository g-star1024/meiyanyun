package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * fin_change_log 财务设置变更日志（B5，JPA ddl-auto）。
 *
 * <p>保存财务设置时按字段 diff 逐行记录（字段中文名、格式化前后值、操作人、时间）；
 * 科目启用/停用同样落日志。仅留痕，不参与资金动账。
 */
@Entity
@Table(name = "fin_change_log")
@Getter @Setter @NoArgsConstructor
public class FinChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "log_by", nullable = false, length = 64)
    private String logBy;

    @Column(name = "log_at", nullable = false)
    private OffsetDateTime logAt;

    @Column(name = "field_label", nullable = false, length = 128)
    private String fieldLabel;

    @Column(name = "old_value", length = 256)
    private String oldValue;

    @Column(name = "new_value", length = 256)
    private String newValue;
}
