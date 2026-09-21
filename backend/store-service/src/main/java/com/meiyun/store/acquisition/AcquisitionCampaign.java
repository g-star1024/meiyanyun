package com.meiyun.store.acquisition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "acquisition_campaign",
    uniqueConstraints = @UniqueConstraint(name = "uk_aq_no", columnNames = {"aq_no", "store_code"}),
    indexes = {
        @Index(name = "idx_aq_store", columnList = "store_code"),
        @Index(name = "idx_aq_status", columnList = "status"),
        @Index(name = "idx_aq_type", columnList = "type"),
    }
)
public class AcquisitionCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aq_no", nullable = false, length = 20)
    private String aqNo;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "exposure", nullable = false)
    private Integer exposure = 0;

    @Column(name = "arrival", nullable = false)
    private Integer arrival = 0;

    @Column(name = "deal", nullable = false)
    private Integer deal = 0;

    @Column(name = "budget_fen", nullable = false)
    private Long budgetFen = 0L;

    @Column(name = "spent_fen", nullable = false)
    private Long spentFen = 0L;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(name = "start_date", nullable = false)
    private OffsetDateTime startDate;

    @Column(name = "end_date", nullable = false)
    private OffsetDateTime endDate;

    @Column(name = "owner", nullable = false, length = 64)
    private String owner;

    @Column(name = "channel", nullable = false, length = 128)
    private String channel;

    @PrePersist
    void fillDefaults() {
        if (exposure == null) exposure = 0;
        if (arrival == null) arrival = 0;
        if (deal == null) deal = 0;
        if (budgetFen == null) budgetFen = 0L;
        if (spentFen == null) spentFen = 0L;
        if (status == null) status = "DRAFT";
        if (channel == null) channel = "私域社群";
    }
}
