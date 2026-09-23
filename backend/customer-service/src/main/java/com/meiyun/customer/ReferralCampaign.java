package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 转介绍活动（P5-B89）。状态 DRAFT/ONGOING/ENDED；store_code 空=全部门店。
 * v1 仅实体＋种子，invited/converted 统计聚合留 v2（04-backlog 登记行）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "referral_campaign", indexes = {
        @Index(name = "idx_rc_status", columnList = "status"),
        @Index(name = "idx_rc_store", columnList = "store_code")
})
public class ReferralCampaign {

    /** 活动号：RC+8 位日期+'-'+6 位当日序号。 */
    @Id
    @Column(name = "campaign_id", length = 24)
    private String campaignId;

    @Column(nullable = false, length = 64)
    private String name;

    /** DRAFT 草稿 / ONGOING 进行中 / ENDED 已结束。 */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "start_at", nullable = false)
    private LocalDate startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDate endAt;

    /** 门店码：NULL=全部门店（连锁活动），填值=单店活动。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(length = 256)
    private String remark;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "DRAFT";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
