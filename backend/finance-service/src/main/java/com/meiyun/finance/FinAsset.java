package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * fin_asset 设备资产台账（B11 月结成本结转，JPA ddl-auto）。
 *
 * <p>轻量固定资产档案（不做完整固定资产模块）：直线法月折旧
 * = round(original_value × (100 - salvage_rate) / 100 / useful_months)，
 * 起折月 start_month 起按月计提，处置（status=DISPOSED）后停折。
 * 金额 Long「分」；salvage_rate 为百分比整数（默认 5）；月份契约 yyyy-MM-01。
 */
@Entity
@Table(name = "fin_asset")
@Getter @Setter @NoArgsConstructor
public class FinAsset {

    @Id
    @Column(name = "asset_id", length = 24)
    private String assetId;

    @Column(name = "asset_name", nullable = false, length = 64)
    private String assetName;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 原值（分）。 */
    @Column(name = "original_value", nullable = false)
    private Long originalValue;

    /** 残值率（百分比，默认 5）。 */
    @Column(name = "salvage_rate", nullable = false)
    private Integer salvageRate = 5;

    /** 折旧月限（如 120 = 10 年）。 */
    @Column(name = "useful_months", nullable = false)
    private Integer usefulMonths;

    /** 起折月（yyyy-MM-01）。 */
    @Column(name = "start_month", nullable = false)
    private LocalDate startMonth;

    /** IN_USE 在用 / DISPOSED 已处置（处置月起停折）。 */
    @Column(nullable = false, length = 16)
    private String status = "IN_USE";

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_by", length = 32)
    private String updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    /** 直线法月折旧额（分）：round(原值 × (100-残值率)/100 / 月限)。 */
    public long monthlyDepreciation() {
        if (originalValue == null || usefulMonths == null || usefulMonths <= 0) return 0L;
        int rate = salvageRate == null ? 5 : salvageRate;
        long depreciable = Math.round(originalValue * (100 - rate) / 100.0);
        return Math.round(depreciable / (double) usefulMonths);
    }
}
