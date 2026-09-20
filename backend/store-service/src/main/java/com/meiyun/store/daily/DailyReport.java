package com.meiyun.store.daily;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "daily_report",
        uniqueConstraints = @UniqueConstraint(name = "uk_dr_date", columnNames = {"store_code", "report_date"}),
        indexes = {
                @Index(name = "idx_dr_store", columnList = "store_code"),
                @Index(name = "idx_dr_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
public class DailyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "report_date", nullable = false)
    private LocalDate date;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "footfall", nullable = false)
    private Integer footfall = 0;

    @Column(name = "orders", nullable = false)
    private Integer orders = 0;

    @Column(name = "services", nullable = false)
    private Integer services = 0;

    @Column(name = "inventory_alerts", nullable = false)
    private Integer inventoryAlerts = 0;

    @Convert(converter = IntListJsonConverter.class)
    @Column(name = "hourly_json", columnDefinition = "jsonb", nullable = false)
    private List<Integer> hourly = List.of();

    @Column(name = "exceptions", length = 512)
    private String exceptions;

    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "submitted_by", length = 64)
    private String submittedBy;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Convert(converter = TimelineJsonConverter.class)
    @Column(name = "timeline_json", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> timeline = List.of();

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (footfall == null) footfall = 0;
        if (orders == null) orders = 0;
        if (services == null) services = 0;
        if (inventoryAlerts == null) inventoryAlerts = 0;
        if (hourly == null) hourly = List.of();
        if (timeline == null) timeline = List.of();
    }
}
