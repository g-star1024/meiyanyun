package com.meiyun.store.handover;

import com.meiyun.store.daily.TimelineJsonConverter;
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
@Table(name = "handover",
        uniqueConstraints = @UniqueConstraint(name = "uk_ho_no", columnNames = {"ho_no"}),
        indexes = {
                @Index(name = "idx_ho_store", columnList = "store_code"),
                @Index(name = "idx_ho_status", columnList = "status"),
                @Index(name = "idx_ho_date", columnList = "handover_date")
        })
@Getter
@Setter
@NoArgsConstructor
public class Handover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ho_no", nullable = false, length = 24)
    private String handoverNo;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "shift", nullable = false, length = 16)
    private String shift;

    @Column(name = "handover_date", nullable = false)
    private LocalDate date;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "from_name", nullable = false, length = 64)
    private String fromName;

    @Column(name = "to_name", nullable = false, length = 64)
    private String toName;

    @Column(name = "revenue_amount", nullable = false)
    private Long revenueAmount = 0L;

    @Column(name = "order_count", nullable = false)
    private Integer orderCount = 0;

    @Column(name = "arrival_count", nullable = false)
    private Integer arrivalCount = 0;

    @Column(name = "important_note", length = 512)
    private String importantNote;

    @Column(name = "cash_note", length = 512)
    private String cashNote;

    @Column(name = "equipment_note", length = 512)
    private String equipmentNote;

    @Column(name = "confirm_note", length = 512)
    private String confirmNote;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Convert(converter = TimelineJsonConverter.class)
    @Column(name = "timeline_json", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> timeline = List.of();

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (revenueAmount == null) revenueAmount = 0L;
        if (orderCount == null) orderCount = 0;
        if (arrivalCount == null) arrivalCount = 0;
        if (timeline == null) timeline = List.of();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
