package com.meiyun.store.weekly;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "weekly_report",
    uniqueConstraints = @UniqueConstraint(name = "uk_week_no", columnNames = {"week_no", "store_code"}),
    indexes = {
        @Index(name = "idx_wr_store", columnList = "store_code"),
        @Index(name = "idx_wr_status", columnList = "status"),
        @Index(name = "idx_wr_start", columnList = "start_date"),
    }
)
public class WeeklyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "week_no", nullable = false, length = 16)
    private String weekNo;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "revenue_fen", nullable = false)
    private Long revenueFen = 0L;

    @Column(name = "prev_revenue_fen", nullable = false)
    private Long prevRevenueFen = 0L;

    @Column(name = "footfall", nullable = false)
    private Integer footfall = 0;

    @Column(name = "orders", nullable = false)
    private Integer orders = 0;

    @Column(name = "new_customers", nullable = false)
    private Integer newCustomers = 0;

    @Column(name = "repurchase_rate", nullable = false)
    private Integer repurchaseRate = 0;

    @Column(name = "highlights", length = 2000)
    private String highlights = "";

    @Column(name = "issues", length = 2000)
    private String issues = "";

    @Column(name = "next_week_plan", length = 2000)
    private String nextWeekPlan = "";

    @Column(name = "status", nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(name = "submitted_by", length = 32)
    private String submittedBy;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @PrePersist
    void fillDefaults() {
        if (revenueFen == null) revenueFen = 0L;
        if (prevRevenueFen == null) prevRevenueFen = 0L;
        if (footfall == null) footfall = 0;
        if (orders == null) orders = 0;
        if (newCustomers == null) newCustomers = 0;
        if (repurchaseRate == null) repurchaseRate = 0;
        if (highlights == null) highlights = "";
        if (issues == null) issues = "";
        if (nextWeekPlan == null) nextWeekPlan = "";
        if (status == null) status = "DRAFT";
    }
}
