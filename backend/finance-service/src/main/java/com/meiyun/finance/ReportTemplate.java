package com.meiyun.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 报表模板（report_template，B49 卡11 M1 报表中心）：id 即业务码 'R01'-'R09'，
 * dimensions/metrics 逗号串存储（view 层拆数组），category/period 六枚举字符串。
 */
@Entity
@Table(name = "report_template")
@Getter @Setter @NoArgsConstructor
public class ReportTemplate {

    @Id
    @Column(name = "id", length = 8)
    private String id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** REVENUE / CUSTOMER / OPERATION / FINANCE / COMPLIANCE / STAFF */
    @Column(name = "category", nullable = false, length = 16)
    private String category;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    /** DAY / WEEK / MONTH / QUARTER / YEAR / RANGE */
    @Column(name = "period", nullable = false, length = 16)
    private String period;

    @Column(name = "dimensions", nullable = false, length = 255)
    private String dimensions;

    @Column(name = "metrics", nullable = false, length = 255)
    private String metrics;

    @Column(name = "subscribed", nullable = false)
    private Boolean subscribed = false;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;
}
