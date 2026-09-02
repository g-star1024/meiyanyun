package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 营销活动。 */
@Entity
@Table(name = "campaign")
@Getter @Setter @NoArgsConstructor
public class Campaign {

    @Id
    @Column(name = "campaign_id", length = 24)
    private String campaignId;

    @Column(name = "campaign_name", nullable = false, length = 64)
    private String campaignName;

    @Column(name = "campaign_type", nullable = false, length = 8)
    private String campaignType;

    @Column(nullable = false, length = 8)
    private String status;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false)
    private Long budget;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
